from pathlib import Path
from typing import List


HERE = Path(__file__).resolve().parent
EXPECTED_DIR = HERE.parent


def normalized_lines(path: Path) -> List[bytes]:
    data = path.read_bytes().replace(b"\r\n", b"\n")
    parts = data.split(b"\n")
    lines = [part + b"\n" for part in parts[:-1]]
    if parts[-1]:
        lines.append(parts[-1])
    return lines


def visible(line: bytes) -> str:
    if line.endswith(b"\n"):
        line = line[:-1]
    return repr(line.decode("utf-8", errors="backslashreplace"))


def compare(test_number: int) -> bool:
    actual_path = HERE / f"actual{test_number}.txt"
    expected_path = EXPECTED_DIR / f"output{test_number}.txt"
    actual = normalized_lines(actual_path)
    expected = normalized_lines(expected_path)

    if actual == expected:
        print(f"[PASS] testfile{test_number} 输出与预期一致")
        return True

    print(f"[FAIL] testfile{test_number} 输出存在差异")
    for line_number in range(max(len(actual), len(expected))):
        actual_line = actual[line_number] if line_number < len(actual) else None
        expected_line = expected[line_number] if line_number < len(expected) else None
        if actual_line != expected_line:
            actual_text = "<缺少该行>" if actual_line is None else visible(actual_line)
            expected_text = "<缺少该行>" if expected_line is None else visible(expected_line)
            print(f"  第 {line_number + 1} 行不同")
            print(f"    actual:   {actual_text}")
            print(f"    expected: {expected_text}")
            break
    return False


def main() -> int:
    all_passed = True
    for number in range(1, 6):
        all_passed = compare(number) and all_passed
    return 0 if all_passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
