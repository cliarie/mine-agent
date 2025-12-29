use std::env;
use std::fs::File;
use std::io::{BufReader, Read};

// Packet IDs (must match PacketCaptureMixin.java)
const PKT_SCREEN_HANDLER_SLOT: u16 = 1;
const PKT_INVENTORY: u16 = 2;
const PKT_OPEN_SCREEN: u16 = 3;
const PKT_CLOSE_SCREEN: u16 = 4;
const PKT_PLAYER_POSITION: u16 = 5;
const PKT_ENTITY_POSITION: u16 = 6;
const PKT_BLOCK_UPDATE: u16 = 7;
const PKT_HELD_ITEM_CHANGE: u16 = 8;

fn packet_name(id: u16) -> &'static str {
    match id {
        PKT_SCREEN_HANDLER_SLOT => "ScreenHandlerSlotUpdate",
        PKT_INVENTORY => "Inventory",
        PKT_OPEN_SCREEN => "OpenScreen",
        PKT_CLOSE_SCREEN => "CloseScreen",
        PKT_PLAYER_POSITION => "PlayerPositionLook",
        PKT_ENTITY_POSITION => "EntityPosition",
        PKT_BLOCK_UPDATE => "BlockUpdate",
        PKT_HELD_ITEM_CHANGE => "HeldItemChange",
        _ => "Unknown",
    }
}

fn main() {
    let args: Vec<String> = env::args().collect();
    if args.len() < 2 {
        eprintln!("Usage: inspect_packets <packets.bin> [max_packets]");
        eprintln!("Example: inspect_packets mod/run/mcap_replay/sessions/20251228T.../packets.bin 50");
        std::process::exit(1);
    }

    let path = &args[1];
    let max_packets: usize = args.get(2).and_then(|s| s.parse().ok()).unwrap_or(100);

    let file = File::open(path).expect("Failed to open packets.bin");
    let mut reader = BufReader::new(file);
    let file_size = std::fs::metadata(path).unwrap().len();

    println!("=== Packets.bin Inspector ===");
    println!("File: {}", path);
    println!("Size: {} bytes", file_size);
    println!();

    // Format per packet: u32 tick, u16 packetId, u16 dataLen, data[]
    let mut packet_count = 0;
    let mut bytes_read: u64 = 0;
    let mut packet_counts: std::collections::HashMap<u16, usize> = std::collections::HashMap::new();

    loop {
        if packet_count >= max_packets {
            println!("... (showing first {} packets, use second arg to show more)", max_packets);
            break;
        }

        // Read header: u32 tick, u16 packetId, u16 dataLen
        let mut header = [0u8; 8];
        match reader.read_exact(&mut header) {
            Ok(_) => {}
            Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => break,
            Err(e) => {
                eprintln!("Error reading header: {}", e);
                break;
            }
        }

        let tick = u32::from_le_bytes([header[0], header[1], header[2], header[3]]);
        let packet_id = u16::from_le_bytes([header[4], header[5]]);
        let data_len = u16::from_le_bytes([header[6], header[7]]);

        // Read packet data
        let mut data = vec![0u8; data_len as usize];
        if let Err(e) = reader.read_exact(&mut data) {
            eprintln!("Error reading packet data: {}", e);
            break;
        }

        bytes_read += 8 + data_len as u64;
        *packet_counts.entry(packet_id).or_insert(0) += 1;

        // Print packet info
        println!(
            "[{}] Tick {:5} | {:25} (id={:2}) | {} bytes | {:?}",
            packet_count,
            tick,
            packet_name(packet_id),
            packet_id,
            data_len,
            if data.len() > 32 {
                format!("{:02x?}...", &data[..32])
            } else {
                format!("{:02x?}", data)
            }
        );

        packet_count += 1;
    }

    println!();
    println!("=== Summary ===");
    println!("Total packets shown: {}", packet_count);
    println!("Bytes read: {} / {}", bytes_read, file_size);
    println!();
    println!("Packet type counts:");
    let mut counts: Vec<_> = packet_counts.iter().collect();
    counts.sort_by_key(|(_, c)| std::cmp::Reverse(*c));
    for (id, count) in counts {
        println!("  {:25}: {}", packet_name(*id), count);
    }
}
