-- Migration: add columns needed for leaderboard service
-- Run this after create_runs_table.sql

-- Player display name (populated from JWT during analytics ingestion)
ALTER TABLE public.runs ADD COLUMN IF NOT EXISTS player_name text;

-- Missing milestone columns (already sent by the mod but not in the original schema)
ALTER TABLE public.runs ADD COLUMN IF NOT EXISTS bastion_enter_tick bigint NOT NULL DEFAULT -1;
ALTER TABLE public.runs ADD COLUMN IF NOT EXISTS blind_travel_tick bigint NOT NULL DEFAULT -1;
ALTER TABLE public.runs ADD COLUMN IF NOT EXISTS eye_spy_tick bigint NOT NULL DEFAULT -1;
ALTER TABLE public.runs ADD COLUMN IF NOT EXISTS blaze_kills int NOT NULL DEFAULT 0;
