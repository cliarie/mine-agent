"""Wait for mine-env to be ready, then run integration test."""
import asyncio
import json
import sys
import time
import websockets

NO_OP = {
    "forward": False, "back": False, "left": False, "right": False,
    "jump": False, "sneak": False, "sprint": False,
    "attack": False, "use": False, "drop": False,
    "inventory_slot": -1, "delta_yaw": 0.0, "delta_pitch": 0.0
}

def action(**overrides):
    a = dict(NO_OP)
    a.update(overrides)
    return a

async def step(ws, act):
    await ws.send(json.dumps(act))
    return json.loads(await ws.recv())

async def run_steps(ws, act, n):
    obs_list = []
    for _ in range(n):
        obs = await step(ws, act)
        obs_list.append(obs)
    return obs_list

PASS = 0
FAIL = 0

def check(name, condition, detail=""):
    global PASS, FAIL
    if condition:
        PASS += 1
        print(f"  PASS: {name}")
    else:
        FAIL += 1
        print(f"  FAIL: {name} -- {detail}")

async def wait_for_server(uri, timeout=120):
    """Keep trying to connect until the server is up and player is in-world."""
    start = time.time()
    while time.time() - start < timeout:
        try:
            ws = await asyncio.wait_for(websockets.connect(uri), timeout=3)
            # send a no-op and check if we get an observation with valid position
            await ws.send(json.dumps(NO_OP))
            raw = await asyncio.wait_for(ws.recv(), timeout=5)
            obs = json.loads(raw)
            if obs.get("tick") is not None:
                return ws
            await ws.close()
        except Exception:
            pass
        await asyncio.sleep(2)
    print("Timed out waiting for mine-env server")
    sys.exit(1)

async def test():
    uri = "ws://localhost:25576"
    print("Waiting for mine-env server + player in world...")
    ws = await wait_for_server(uri)
    print("Connected to mine-env\n")

    try:
        # --- Baseline ---
        print("[1] Baseline (no-op)")
        baseline = await run_steps(ws, NO_OP, 5)
        start = baseline[-1]
        check("got observation", start["tick"] >= 0, f"tick={start['tick']}")
        check("health > 0", start["health"] > 0, f"health={start['health']}")
        check("alive", start["alive"] is True)
        print()

        # --- Move forward ---
        print("[2] Move forward (20 ticks)")
        obs_before = (await run_steps(ws, NO_OP, 3))[-1]
        obs_list = await run_steps(ws, action(forward=True), 20)
        obs_after = obs_list[-1]
        dx = abs(obs_after["x"] - obs_before["x"])
        dz = abs(obs_after["z"] - obs_before["z"])
        moved = dx > 0.1 or dz > 0.1
        check("player moved", moved, f"dx={dx:.3f} dz={dz:.3f}")
        await run_steps(ws, NO_OP, 5)
        print()

        # --- Jump ---
        print("[3] Jump (10 ticks)")
        obs_before_jump = (await run_steps(ws, NO_OP, 5))[-1]
        y_before = obs_before_jump["y"]
        obs_list = await run_steps(ws, action(jump=True), 10)
        max_y = max(o["y"] for o in obs_list)
        check("player jumped", max_y > y_before + 0.1, f"y_before={y_before:.2f} max_y={max_y:.2f}")
        await run_steps(ws, NO_OP, 10)
        print()

        # --- Camera yaw ---
        print("[4] Camera yaw rotation")
        obs_before_yaw = (await run_steps(ws, NO_OP, 3))[-1]
        yaw_before = obs_before_yaw["yaw"]
        await run_steps(ws, action(delta_yaw=45.0), 1)
        obs_after_yaw = (await run_steps(ws, NO_OP, 3))[-1]
        yaw_after = obs_after_yaw["yaw"]
        yaw_diff = abs(yaw_after - yaw_before)
        check("yaw changed", yaw_diff > 10.0, f"before={yaw_before:.1f} after={yaw_after:.1f}")
        print()

        # --- Camera pitch ---
        print("[5] Camera pitch rotation")
        # first reset pitch toward center so we have room to move
        await run_steps(ws, action(delta_pitch=-60.0), 1)
        obs_before_pitch = (await run_steps(ws, NO_OP, 3))[-1]
        pitch_before = obs_before_pitch["pitch"]
        await run_steps(ws, action(delta_pitch=30.0), 1)
        obs_after_pitch = (await run_steps(ws, NO_OP, 3))[-1]
        pitch_after = obs_after_pitch["pitch"]
        pitch_diff = abs(pitch_after - pitch_before)
        check("pitch changed", pitch_diff > 10.0, f"before={pitch_before:.1f} after={pitch_after:.1f}")
        print()

        # --- Hotbar ---
        print("[6] Hotbar slot change")
        obs_before_slot = (await run_steps(ws, NO_OP, 3))[-1]
        slot_before = obs_before_slot["hotbar_slot"]
        target_slot = (slot_before + 3) % 9
        await run_steps(ws, action(inventory_slot=target_slot), 3)
        obs_after_slot = (await run_steps(ws, NO_OP, 3))[-1]
        check("hotbar slot changed", obs_after_slot["hotbar_slot"] == target_slot,
              f"expected={target_slot} got={obs_after_slot['hotbar_slot']}")
        print()

        # --- Reward/done stubs ---
        print("[7] Reward/done stubs")
        obs = (await run_steps(ws, NO_OP, 3))[-1]
        check("reward is 0.0", obs["reward"] == 0.0)
        check("done is false", obs["done"] is False)
        print()

        # --- Summary ---
        print(f"\n{'='*40}")
        print(f"Results: {PASS} passed, {FAIL} failed out of {PASS+FAIL} checks")
        if FAIL == 0:
            print("ALL TESTS PASSED")
        else:
            print("SOME TESTS FAILED")
    finally:
        await ws.close()

asyncio.run(test())
