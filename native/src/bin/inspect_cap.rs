use lz4_flex::decompress_size_prepended;
use std::env;
use std::fs;
use std::io::{self, Read};

fn main() -> io::Result<()> {
    let args: Vec<String> = env::args().collect();
    if args.len() < 2 {
        eprintln!("Usage: inspect_cap <file.cap> [--ticks]");
        std::process::exit(1);
    }

    let path = &args[1];
    let show_ticks = args.get(2).map(|s| s == "--ticks").unwrap_or(false);

    let data = fs::read(path)?;
    if data.len() < 25 {
        eprintln!("File too small to be a valid .cap");
        std::process::exit(1);
    }

    // Parse header
    let magic = &data[0..4];
    if magic != b"MCAP" {
        eprintln!("Invalid magic: {:?}", magic);
        std::process::exit(1);
    }

    let schema_version = u16::from_le_bytes([data[4], data[5]]);
    let start_tick = u32::from_le_bytes([data[6], data[7], data[8], data[9]]);
    let tick_count = u16::from_le_bytes([data[10], data[11]]);
    let codec = data[12];
    let uncompressed_len = u32::from_le_bytes([data[13], data[14], data[15], data[16]]);
    let compressed_len = u32::from_le_bytes([data[17], data[18], data[19], data[20]]);
    let crc32 = u32::from_le_bytes([data[21], data[22], data[23], data[24]]);

    println!("=== Header ===");
    println!("Magic:            MCAP");
    println!("Schema version:   {}", schema_version);
    println!("Start tick:       {}", start_tick);
    println!("Tick count:       {}", tick_count);
    println!("Codec:            {} ({})", codec, if codec == 1 { "LZ4" } else { "unknown" });
    println!("Uncompressed len: {} bytes", uncompressed_len);
    println!("Compressed len:   {} bytes", compressed_len);
    println!("CRC32:            0x{:08X}", crc32);
    println!();

    // Verify CRC
    let payload = &data[25..];
    let mut hasher = crc32fast::Hasher::new();
    hasher.update(payload);
    let computed_crc = hasher.finalize();
    println!("Computed CRC32:   0x{:08X} {}", computed_crc, if computed_crc == crc32 { "✓" } else { "✗ MISMATCH" });

    // Decompress
    if codec == 1 {
        match decompress_size_prepended(payload) {
            Ok(decompressed) => {
                println!("Decompressed:     {} bytes", decompressed.len());

                // Auto-detect record size: try 48 first, fall back to 28
                let record_size = if decompressed.len() % 48 == 0 { 48 }
                    else if decompressed.len() % 28 == 0 { 28 }
                    else { 48 }; // default to new format
                let num_records = decompressed.len() / record_size;
                println!("Record size:      {} bytes", record_size);
                println!("Records:          {}", num_records);
                println!();

                if show_ticks && num_records > 0 {
                    println!("=== Tick Records ===");
                    if record_size == 48 {
                        println!("{:>5}  {:>13}  {:>3} {:>3}  {:>8}  {:>8}  {:>10}  {:>8}  {:>8}  {:>8}  {:>6} {:>3} {:>2}",
                            "Idx", "Flags", "Hot", "Mse", "Yaw", "Pitch", "Tick", "X", "Y", "Z", "HP", "Fd", "Sc");
                        println!("{}", "-".repeat(130));
                    } else {
                        println!("{:>6}  {:>6}  {:>3}  {:>8}  {:>8}  {:>10}", "Index", "Flags", "Hot", "Yaw", "Pitch", "Tick");
                        println!("{}", "-".repeat(60));
                    }

                    let max_show = 20.min(num_records);
                    for i in 0..max_show {
                        let off = i * record_size;
                        let flags = u16::from_le_bytes([decompressed[off], decompressed[off + 1]]);
                        let hotbar = decompressed[off + 2];

                        let flag_str = format!(
                            "{}{}{}{}{}{}{}{}{}{}{}{}{}",
                            if flags & 1 != 0 { "W" } else { "." },
                            if flags & 2 != 0 { "S" } else { "." },
                            if flags & 4 != 0 { "A" } else { "." },
                            if flags & 8 != 0 { "D" } else { "." },
                            if flags & 16 != 0 { "J" } else { "." },
                            if flags & 32 != 0 { "C" } else { "." },
                            if flags & 64 != 0 { "R" } else { "." },
                            if flags & 128 != 0 { "I" } else { "." }, // screen open
                            if flags & 256 != 0 { "H" } else { "." }, // arm swing
                            if flags & 512 != 0 { "K" } else { "." }, // attack
                            if flags & 1024 != 0 { "U" } else { "." }, // use
                            if flags & 2048 != 0 { "G" } else { "." }, // on ground
                            if flags & 4096 != 0 { "~" } else { "." }, // in water
                        );

                        if record_size == 48 {
                            let mouse_btn = decompressed[off + 3];
                            let yaw_fp = i16::from_le_bytes([decompressed[off + 4], decompressed[off + 5]]);
                            let pitch_fp = i16::from_le_bytes([decompressed[off + 6], decompressed[off + 7]]);
                            let tick = u32::from_le_bytes([decompressed[off + 8], decompressed[off + 9], decompressed[off + 10], decompressed[off + 11]]);
                            let x = f32::from_le_bytes([decompressed[off + 12], decompressed[off + 13], decompressed[off + 14], decompressed[off + 15]]);
                            let y = f32::from_le_bytes([decompressed[off + 16], decompressed[off + 17], decompressed[off + 18], decompressed[off + 19]]);
                            let z = f32::from_le_bytes([decompressed[off + 20], decompressed[off + 21], decompressed[off + 22], decompressed[off + 23]]);
                            let health = f32::from_le_bytes([decompressed[off + 24], decompressed[off + 25], decompressed[off + 26], decompressed[off + 27]]);
                            let food = decompressed[off + 28];
                            let screen_type = decompressed[off + 29];

                            let yaw = yaw_fp as f32 / 100.0;
                            let pitch = pitch_fp as f32 / 100.0;

                            println!("{:>5}  {:>13}  {:>3} {:>3}  {:>8.2}  {:>8.2}  {:>10}  {:>8.1}  {:>8.1}  {:>8.1}  {:>6.1} {:>3} {:>2}",
                                i, flag_str, hotbar, mouse_btn, yaw, pitch, tick, x, y, z, health, food, screen_type);
                        } else {
                            let yaw_fp = i16::from_le_bytes([decompressed[off + 4], decompressed[off + 5]]);
                            let pitch_fp = i16::from_le_bytes([decompressed[off + 6], decompressed[off + 7]]);
                            let tick = u32::from_le_bytes([decompressed[off + 8], decompressed[off + 9], decompressed[off + 10], decompressed[off + 11]]);
                            let yaw = yaw_fp as f32 / 100.0;
                            let pitch = pitch_fp as f32 / 100.0;
                            println!("{:>6}  {:>6}  {:>3}  {:>8.2}  {:>8.2}  {:>10}", i, flag_str, hotbar, yaw, pitch, tick);
                        }
                    }

                    if num_records > max_show {
                        println!("... ({} more records)", num_records - max_show);
                    }
                }
            }
            Err(e) => {
                eprintln!("Decompression failed: {}", e);
            }
        }
    }

    Ok(())
}
