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

# Validate numeric inputs using awk (supports integer and decimal seconds/rates)
if ! awk -v sr="$SAMPLE_RATE" -v st="$START_SEC" -v ln="$LENGTH_SEC" '
BEGIN {
    if (sr <= 0 || st < 0 || ln <= 0) exit 1;
    exit 0;
}'; then
    echo "Error: sample_rate and length_sec must be greater than 0, and start_sec must be 0 or greater." >&2
    exit 1
fi

# Calculate sample offsets (1 IQ sample = 16 bits I + 16 bits Q = 4 bytes)
SKIP_SAMPLES=$(awk -v sr="$SAMPLE_RATE" -v st="$START_SEC" 'BEGIN { printf "%.0f", sr * st }')
COUNT_SAMPLES=$(awk -v sr="$SAMPLE_RATE" -v ln="$LENGTH_SEC" 'BEGIN { printf "%.0f", sr * ln }')

# Build output filename: <original file name>_S<start time>_L<length>.cs16
DIR_NAME=$(dirname "$INPUT_FILE")
BASE_NAME=$(basename "$INPUT_FILE")
STEM_NAME="${BASE_NAME%.cs16}"
OUTPUT_FILE="${DIR_NAME}/${STEM_NAME}_S${START_SEC}_L${LENGTH_SEC}.cs16"

# Extract exact IQ samples (4 bytes per sample) without modifying values
dd if="$INPUT_FILE" of="$OUTPUT_FILE" bs=4 skip="$SKIP_SAMPLES" count="$COUNT_SAMPLES"

echo "Saved: $OUTPUT_FILE"