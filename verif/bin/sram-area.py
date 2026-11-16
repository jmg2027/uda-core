#!/usr/bin/env python3
"""Split mapped cell area into standard cells and explicit SRAM macro instances."""
import argparse
import json
from pathlib import Path
import re


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("statistics", type=Path)
    parser.add_argument("macro_liberty", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    macro_text = args.macro_liberty.read_text()
    macro = re.search(r"\bcell\s*\(\s*([^\s)]+)\s*\)", macro_text).group(1).strip('"')
    macro_area = float(re.search(r"\barea\s*:\s*([0-9.eE+-]+)", macro_text).group(1))
    statistics = json.loads(args.statistics.read_text())
    design = statistics["design"]
    counts = design["num_cells_by_type"]
    macro_count = counts.get(macro, 0)
    total = design["area"]
    area = macro_count * macro_area
    result = {
        "macro": macro,
        "macro_count": macro_count,
        "macro_instance_area_um2": macro_area,
        "macro_area_um2": area,
        "standard_cell_area_um2": total - area,
        "total_mapped_area_um2": total,
        "standard_cell_count": sum(n for cell, n in counts.items() if cell.startswith("sky130_fd_sc_hd__")),
        "standard_cell_flops": sum(n for cell, n in counts.items() if re.match(r"sky130_fd_sc_hd__(?:e?df|sd)", cell)),
        "model": "OpenRAM macro LEF/liberty footprint plus mapped standard cells; no floorplan, clock tree or placement area",
    }
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, separators=(",", ":")))


if __name__ == "__main__":
    main()
