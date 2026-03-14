import asyncio
import json
import websockets

async def test():
    uri = "ws://localhost:25576"
    async with websockets.connect(uri) as ws:
        print("Connected to mine-env")

        for i in range(100):
            # send a no-op action
            action = {
                "forward": False, "back": False, "left": False, "right": False,
                "jump": False, "sneak": False, "sprint": False,
                "attack": False, "use": False, "drop": False,
                "inventory_slot": -1, "delta_yaw": 0.0, "delta_pitch": 0.0
            }
            await ws.send(json.dumps(action))

            obs = json.loads(await ws.recv())
            print(f"tick={obs['tick']} pos=({obs['x']:.1f},{obs['y']:.1f},{obs['z']:.1f}) "
                  f"health={obs['health']} dim={obs['dimension']}")

            await asyncio.sleep(0.05)  # ~20hz

asyncio.run(test())
