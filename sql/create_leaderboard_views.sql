-- =============================================================================
-- Leaderboard views & functions for mine-agent speedrun analytics
-- Run this in your Supabase SQL Editor AFTER create_runs_table.sql
--
-- Depends on: public.runs
-- Idempotent: safe to re-run (uses DROP IF EXISTS / CREATE OR REPLACE)
-- =============================================================================


-- =============================================================================
-- 1. v_player_stats  (MATERIALIZED VIEW — aggregated per-player stats)
-- =============================================================================
-- Materialized for performance; refresh periodically (see bottom of file).

DROP MATERIALIZED VIEW IF EXISTS public.v_player_stats CASCADE;

CREATE MATERIALIZED VIEW public.v_player_stats AS
WITH completed_runs AS (
  SELECT
    player_id,
    run_id,
    duration_ticks,
    blaze_rods_used,
    pearls_used,
    deaths,
    efficiency_score,
    nether_score,
    ingested_at
  FROM public.runs
  WHERE completed = true
),
-- Find each player's personal-best run (min duration among completed runs).
-- In case of ties, pick the earliest ingested run.
pb AS (
  SELECT DISTINCT ON (player_id)
    player_id,
    run_id       AS pb_run_id,
    duration_ticks AS pb_duration_ticks
  FROM completed_runs
  ORDER BY player_id, duration_ticks ASC, ingested_at ASC
)
SELECT
  r.player_id,

  -- Totals
  COUNT(*)::int                                            AS total_runs,
  COUNT(*) FILTER (WHERE r.completed)::int                 AS completed_runs,

  -- Personal best
  pb.pb_duration_ticks,
  pb.pb_run_id,

  -- Averages (completed runs only)
  ROUND(AVG(cr.duration_ticks))::bigint                    AS avg_duration_ticks,
  ROUND(AVG(cr.blaze_rods_used), 2)                        AS avg_blaze_rods,
  ROUND(AVG(cr.pearls_used), 2)                            AS avg_pearls,
  ROUND(AVG(cr.deaths), 2)                                 AS deaths_per_run,
  ROUND(AVG(cr.efficiency_score), 2)                       AS efficiency_score,
  ROUND(AVG(cr.nether_score), 2)                           AS nether_score,

  -- Activity
  MAX(r.ingested_at)                                       AS last_run_at

FROM public.runs r
LEFT JOIN completed_runs cr USING (player_id, run_id)
LEFT JOIN pb              USING (player_id)
GROUP BY r.player_id, pb.pb_duration_ticks, pb.pb_run_id;

-- Unique index required for CONCURRENT refresh
CREATE UNIQUE INDEX IF NOT EXISTS idx_v_player_stats_player
  ON public.v_player_stats (player_id);


-- =============================================================================
-- 2. v_leaderboard  (VIEW — ranked leaderboard built on v_player_stats)
-- =============================================================================
-- Only players who have at least one completed run appear on the leaderboard.

CREATE OR REPLACE VIEW public.v_leaderboard AS
SELECT
  ps.*,
  DENSE_RANK() OVER (ORDER BY ps.pb_duration_ticks ASC) AS rank
FROM public.v_player_stats ps
WHERE ps.completed_runs > 0;


-- =============================================================================
-- 3. get_leaderboard(p_category, p_limit, p_offset)
--    Returns JSON matching a LeaderboardResponse shape:
--    {
--      entries: [ { rank, player_id, player_name, pb_duration_ticks, ... } ],
--      total_players: int,
--      category: text
--    }
--
--    NOTE: player_name is NOT stored in runs. We fall back to player_id.
--    The iodine server can join against a players table for display names.
-- =============================================================================

CREATE OR REPLACE FUNCTION public.get_leaderboard(
  p_category text    DEFAULT 'any%',
  p_limit    int     DEFAULT 25,
  p_offset   int     DEFAULT 0
)
RETURNS json
LANGUAGE sql
STABLE
SECURITY DEFINER
AS $$
  WITH filtered AS (
    -- Re-derive leaderboard for the requested category from raw runs
    SELECT
      r.player_id,
      COUNT(*)::int                                          AS total_runs,
      COUNT(*) FILTER (WHERE r.completed)::int               AS completed_runs,
      MIN(r.duration_ticks) FILTER (WHERE r.completed)       AS pb_duration_ticks,
      ROUND(AVG(r.duration_ticks) FILTER (WHERE r.completed))::bigint AS avg_duration_ticks,
      ROUND(AVG(r.blaze_rods_used) FILTER (WHERE r.completed), 2)    AS avg_blaze_rods,
      ROUND(AVG(r.pearls_used) FILTER (WHERE r.completed), 2)        AS avg_pearls,
      ROUND(AVG(r.deaths) FILTER (WHERE r.completed), 2)             AS deaths_per_run,
      ROUND(AVG(r.efficiency_score) FILTER (WHERE r.completed), 2)   AS efficiency_score,
      ROUND(AVG(r.nether_score) FILTER (WHERE r.completed), 2)       AS nether_score,
      MAX(r.ingested_at)                                     AS last_run_at
    FROM public.runs r
    WHERE r.category = p_category
    GROUP BY r.player_id
    HAVING COUNT(*) FILTER (WHERE r.completed) > 0
  ),
  ranked AS (
    SELECT
      f.*,
      DENSE_RANK() OVER (ORDER BY f.pb_duration_ticks ASC) AS rank
    FROM filtered f
  ),
  total AS (
    SELECT COUNT(*)::int AS total_players FROM ranked
  )
  SELECT json_build_object(
    'entries', COALESCE((
      SELECT json_agg(row_to_json(page.*))
      FROM (
        SELECT
          rk.rank,
          rk.player_id,
          rk.player_id          AS player_name, -- fallback; join against players table for real name
          rk.pb_duration_ticks,
          rk.avg_duration_ticks,
          rk.total_runs,
          rk.completed_runs,
          rk.avg_blaze_rods,
          rk.avg_pearls,
          rk.deaths_per_run,
          rk.efficiency_score,
          rk.nether_score,
          rk.last_run_at
        FROM ranked rk
        ORDER BY rk.rank ASC, rk.player_id ASC
        LIMIT p_limit
        OFFSET p_offset
      ) page
    ), '[]'::json),
    'total_players', (SELECT total_players FROM total),
    'category',      p_category
  );
$$;


-- =============================================================================
-- 4. get_player_profile(p_player_id)
--    Returns JSON with all PlayerProfile fields derivable from runs:
--    {
--      player_id, player_name,
--      total_runs, completed_runs, win_rate,
--      pb_duration_ticks, pb_run_id,
--      avg_duration_ticks, avg_blaze_rods, avg_pearls,
--      deaths_per_run, efficiency_score, nether_score,
--      best_category_ranks: { "any%": <rank> },
--      recent_runs: [ ... last 10 ... ],
--      last_run_at
--    }
-- =============================================================================

CREATE OR REPLACE FUNCTION public.get_player_profile(p_player_id text)
RETURNS json
LANGUAGE sql
STABLE
SECURITY DEFINER
AS $$
  WITH player_all AS (
    SELECT *
    FROM public.runs
    WHERE player_id = p_player_id
  ),
  player_completed AS (
    SELECT *
    FROM player_all
    WHERE completed = true
  ),
  -- Personal best (overall, across all categories)
  pb AS (
    SELECT run_id AS pb_run_id, duration_ticks AS pb_duration_ticks
    FROM player_completed
    ORDER BY duration_ticks ASC, ingested_at ASC
    LIMIT 1
  ),
  -- Aggregate stats
  agg AS (
    SELECT
      COUNT(*)::int                                                  AS total_runs,
      COUNT(*) FILTER (WHERE completed)::int                         AS completed_runs,
      ROUND(
        COUNT(*) FILTER (WHERE completed)::numeric
        / GREATEST(COUNT(*), 1), 4
      )                                                              AS win_rate,
      ROUND(AVG(duration_ticks) FILTER (WHERE completed))::bigint    AS avg_duration_ticks,
      ROUND(AVG(blaze_rods_used) FILTER (WHERE completed), 2)        AS avg_blaze_rods,
      ROUND(AVG(pearls_used) FILTER (WHERE completed), 2)            AS avg_pearls,
      ROUND(AVG(deaths) FILTER (WHERE completed), 2)                 AS deaths_per_run,
      ROUND(AVG(efficiency_score) FILTER (WHERE completed), 2)       AS efficiency_score,
      ROUND(AVG(nether_score) FILTER (WHERE completed), 2)           AS nether_score,
      MAX(ingested_at)                                               AS last_run_at
    FROM player_all
  ),
  -- Per-category rank for the player
  category_ranks AS (
    SELECT json_object_agg(cat_rank.category, cat_rank.rank) AS ranks
    FROM (
      SELECT
        lb.category,
        lb.rank
      FROM (
        -- Build a mini-leaderboard per category
        SELECT
          r.category,
          r.player_id,
          DENSE_RANK() OVER (
            PARTITION BY r.category
            ORDER BY MIN(r.duration_ticks) FILTER (WHERE r.completed) ASC
          ) AS rank
        FROM public.runs r
        GROUP BY r.category, r.player_id
        HAVING COUNT(*) FILTER (WHERE r.completed) > 0
      ) lb
      WHERE lb.player_id = p_player_id
    ) cat_rank
  ),
  -- Last 10 runs
  recent AS (
    SELECT COALESCE(json_agg(row_to_json(rr.*)), '[]'::json) AS runs
    FROM (
      SELECT
        run_id,
        session_id,
        category,
        completed,
        outcome,
        started_at_ms,
        duration_ticks,
        overworld_ticks,
        nether_ticks,
        end_ticks,
        portal_build_tick,
        fortress_enter_tick,
        stronghold_enter_tick,
        dragon_enter_tick,
        kill_tick,
        blaze_rods_used,
        blaze_rods_collected,
        pearls_used,
        beds_used,
        gold_traded,
        deaths,
        efficiency_score,
        nether_score,
        seed_quality,
        mod_version,
        ingested_at
      FROM player_all
      ORDER BY ingested_at DESC
      LIMIT 10
    ) rr
  )
  SELECT json_build_object(
    'player_id',           p_player_id,
    'player_name',         p_player_id,  -- fallback; real name from players table
    'total_runs',          agg.total_runs,
    'completed_runs',      agg.completed_runs,
    'win_rate',            agg.win_rate,
    'pb_duration_ticks',   pb.pb_duration_ticks,
    'pb_run_id',           pb.pb_run_id,
    'avg_duration_ticks',  agg.avg_duration_ticks,
    'avg_blaze_rods',      agg.avg_blaze_rods,
    'avg_pearls',          agg.avg_pearls,
    'deaths_per_run',      agg.deaths_per_run,
    'efficiency_score',    agg.efficiency_score,
    'nether_score',        agg.nether_score,
    'best_category_ranks', COALESCE(category_ranks.ranks, '{}'::json),
    'recent_runs',         recent.runs,
    'last_run_at',         agg.last_run_at
  )
  FROM agg
  CROSS JOIN pb
  CROSS JOIN category_ranks
  CROSS JOIN recent;
$$;


-- =============================================================================
-- 5. get_community_stats()
--    Returns JSON with aggregate community-wide statistics:
--    {
--      total_players, total_runs, total_completed,
--      overall_completion_rate,
--      fastest_run_ticks, fastest_run_id,
--      avg_completion_ticks,
--      total_deaths, total_blaze_rods, total_pearls, total_beds, total_gold_traded,
--      runs_today, active_players_today,
--      category_breakdown: [ { category, runs, completed, fastest_ticks } ]
--    }
-- =============================================================================

CREATE OR REPLACE FUNCTION public.get_community_stats()
RETURNS json
LANGUAGE sql
STABLE
SECURITY DEFINER
AS $$
  WITH overall AS (
    SELECT
      COUNT(DISTINCT player_id)::int                                AS total_players,
      COUNT(*)::int                                                 AS total_runs,
      COUNT(*) FILTER (WHERE completed)::int                        AS total_completed,
      ROUND(
        COUNT(*) FILTER (WHERE completed)::numeric
        / GREATEST(COUNT(*), 1), 4
      )                                                             AS overall_completion_rate,
      ROUND(AVG(duration_ticks) FILTER (WHERE completed))::bigint   AS avg_completion_ticks,
      SUM(deaths)::bigint                                           AS total_deaths,
      SUM(blaze_rods_collected)::bigint                             AS total_blaze_rods,
      SUM(pearls_used)::bigint                                      AS total_pearls,
      SUM(beds_used)::bigint                                        AS total_beds,
      SUM(gold_traded)::bigint                                      AS total_gold_traded,
      COUNT(*) FILTER (
        WHERE ingested_at >= (now() - interval '24 hours')
      )::int                                                        AS runs_today,
      COUNT(DISTINCT player_id) FILTER (
        WHERE ingested_at >= (now() - interval '24 hours')
      )::int                                                        AS active_players_today
    FROM public.runs
  ),
  -- Fastest completed run globally
  fastest AS (
    SELECT run_id AS fastest_run_id, duration_ticks AS fastest_run_ticks
    FROM public.runs
    WHERE completed = true
    ORDER BY duration_ticks ASC, ingested_at ASC
    LIMIT 1
  ),
  -- Per-category breakdown
  by_category AS (
    SELECT COALESCE(json_agg(row_to_json(cb.*)), '[]'::json) AS breakdown
    FROM (
      SELECT
        category,
        COUNT(*)::int                                          AS runs,
        COUNT(*) FILTER (WHERE completed)::int                 AS completed,
        MIN(duration_ticks) FILTER (WHERE completed)           AS fastest_ticks
      FROM public.runs
      GROUP BY category
      ORDER BY category
    ) cb
  )
  SELECT json_build_object(
    'total_players',          o.total_players,
    'total_runs',             o.total_runs,
    'total_completed',        o.total_completed,
    'overall_completion_rate', o.overall_completion_rate,
    'fastest_run_ticks',      f.fastest_run_ticks,
    'fastest_run_id',         f.fastest_run_id,
    'avg_completion_ticks',   o.avg_completion_ticks,
    'total_deaths',           o.total_deaths,
    'total_blaze_rods',       o.total_blaze_rods,
    'total_pearls',           o.total_pearls,
    'total_beds',             o.total_beds,
    'total_gold_traded',      o.total_gold_traded,
    'runs_today',             o.runs_today,
    'active_players_today',   o.active_players_today,
    'category_breakdown',     bc.breakdown
  )
  FROM overall o
  CROSS JOIN fastest f
  CROSS JOIN by_category bc;
$$;


-- =============================================================================
-- 6. v_current_streaks  (VIEW — current win streak per player)
--    Counts consecutive completed (outcome = 'win') runs from the most recent
--    run backwards. The streak breaks on any 'death' or 'quit' outcome.
-- =============================================================================

CREATE OR REPLACE VIEW public.v_current_streaks AS
WITH ordered_runs AS (
  -- Number each player's runs from most recent to oldest
  SELECT
    player_id,
    run_id,
    outcome,
    ingested_at,
    ROW_NUMBER() OVER (
      PARTITION BY player_id
      ORDER BY ingested_at DESC
    ) AS rn
  FROM public.runs
),
-- Find the position of the first non-win run (streak breaker)
first_break AS (
  SELECT
    player_id,
    MIN(rn) AS break_at  -- earliest (most recent) non-win position
  FROM ordered_runs
  WHERE outcome <> 'win'
  GROUP BY player_id
)
SELECT
  o.player_id,
  -- If the very first (most recent) run is not a win, streak = 0.
  -- If no break exists at all, every run is a win → streak = total runs.
  COALESCE(fb.break_at - 1, MAX(o.rn))::int AS current_streak,
  -- Timestamp of the most recent run in the streak (for display/sorting)
  MAX(o.ingested_at) FILTER (WHERE o.rn = 1) AS streak_last_run_at
FROM ordered_runs o
LEFT JOIN first_break fb USING (player_id)
GROUP BY o.player_id, fb.break_at;


-- =============================================================================
-- 7. Refresh materialized view
-- =============================================================================

-- Initial population / immediate refresh
REFRESH MATERIALIZED VIEW public.v_player_stats;

-- ┌──────────────────────────────────────────────────────────────────────────┐
-- │  PERIODIC REFRESH (recommended: every 5 minutes via pg_cron)            │
-- │                                                                         │
-- │  Install pg_cron (enabled by default on Supabase):                      │
-- │    SELECT cron.schedule(                                                │
-- │      'refresh-player-stats',          -- job name                       │
-- │      '*/5 * * * *',                   -- every 5 minutes                │
-- │      $$REFRESH MATERIALIZED VIEW CONCURRENTLY public.v_player_stats$$   │
-- │    );                                                                   │
-- │                                                                         │
-- │  CONCURRENTLY requires the unique index on player_id (created above).   │
-- │  It allows reads during refresh — no downtime for the leaderboard.      │
-- └──────────────────────────────────────────────────────────────────────────┘


-- =============================================================================
-- 8. Grant access to Supabase default roles
-- =============================================================================

-- Materialized view
GRANT SELECT ON public.v_player_stats    TO anon, authenticated;
-- Regular views
GRANT SELECT ON public.v_leaderboard     TO anon, authenticated;
GRANT SELECT ON public.v_current_streaks TO anon, authenticated;
-- Functions
GRANT EXECUTE ON FUNCTION public.get_leaderboard(text, int, int)  TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.get_player_profile(text)         TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.get_community_stats()            TO anon, authenticated;
