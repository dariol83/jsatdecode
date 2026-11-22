#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 4 ]]; then
    echo "Usage: $0 <input_cs16_file> <sample_rate> <start_sec> <length_sec>" >&2
    exit 1
fi

INPUT_FILE="$1"
SAMPLE_RATE="$2"
START_SEC="$3"
LENGTH_SEC="$4"

if [[ ! -f "$INPUT_FILE" ]]; then
    echo "Error: Input file '$INPUT_FILE' does not exist." >&2
    exit 1
fi

# Validate positive integers for sample_rate and length_sec, and non-negative integer for start_sec
if ! [[ "$SAMPLE_RATE" =~ ^[1-9][0-9]*$ ]]; then
    echo "Error: sample_rate must be a positive integer." >&2
    exit 1
fi

if ! [[ "$START_SEC" =~ ^[0-9]+$ ]]; then
    echo "Error: start_sec must be a non-negative integer (whole seconds)." >&2
    exit 1
fi

if ! [[ "$LENGTH_SEC" =~ ^[1-9][0-9]*$ ]]; then
    echo "Error: length_sec must be a positive integer (whole seconds)." >&2
    exit 1
fi

# 1 IQ sample = 16 bits I + 16 bits Q = 4 bytes
# 1 second block = sample_rate * 4 bytes
BLOCK_SIZE=$(( SAMPLE_RATE * 4 ))

# Build output filename: <original file name>_S<start time>_L<length>.cs16
DIR_NAME=$(dirname "$INPUT_FILE")
BASE_NAME=$(basename "$INPUT_FILE")
STEM_NAME="${BASE_NAME%.cs16}"
OUTPUT_FILE="${DIR_NAME}/${STEM_NAME}_S${START_SEC}_L${LENGTH_SEC}.cs16"

# Extract whole-second blocks without modifying sample values
dd if="$INPUT_FILE" of="$OUTPUT_FILE" bs="$BLOCK_SIZE" skip="$START_SEC" count="$LENGTH_SEC"

echo "Saved: $OUTPUT_FILE"