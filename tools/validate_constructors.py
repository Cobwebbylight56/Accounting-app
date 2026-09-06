#!/usr/bin/env python3
"""Checks that every data-class construction passes the arguments it must.

WHY THIS EXISTS

A missing constructor argument is a compile error, and the compiler is in CI
rather than here, so it costs a five-minute round trip to find out. It is also
easy to get wrong from a quick read: an annotated property such as

    @ColumnInfo(name = "person_id") val personId: Long?,

has an "=" in it and looks like it carries a default when it does not. That
exact misreading shipped a broken test.

WHAT IT CHECKS

For every `data class` declared under app/src/main, the parameters with no
default are collected. Every construction of that class anywhere in the project
is then checked to name each of them.

Only call sites written entirely with named arguments are checked, which is the
style this codebase uses throughout. Anything positional is skipped rather than
guessed at: a wrong complaint here would be worse than a missed one, since the
compiler still catches what this does not.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAIN = ROOT / "app/src/main/java"
SOURCES = [ROOT / "app/src/main/java", ROOT / "app/src/test/java"]

ANNOTATION = re.compile(r"@\w+(?:\([^()]*\))?")
DECLARATION = re.compile(r"\bdata class (\w+)\s*\(")


def strip_annotations(text: str) -> str:
    """Removes annotations so their `=` cannot be mistaken for a default."""
    previous = None
    while previous != text:
        previous = text
        text = ANNOTATION.sub("", text)
    return text


def balanced(text: str, start: int) -> tuple[str, int]:
    """The contents of the bracket opening at [start], and where it closes."""
    depth = 0
    for index in range(start, len(text)):
        if text[index] == "(":
            depth += 1
        elif text[index] == ")":
            depth -= 1
            if depth == 0:
                return text[start + 1 : index], index
    return "", len(text)


def split_top_level(text: str) -> list[str]:
    """Splits on commas that are not inside brackets or quotes.

    Angle brackets are deliberately not tracked. Inside an argument list "<"
    and ">" are comparisons far more often than generics, and counting them as
    brackets merges `a = if (x < 0) p else q, b = ...` into a single argument —
    which then looks like a missing argument that is in fact right there.
    """
    parts, depth, quote, current = [], 0, False, []
    for character in text:
        if character == '"':
            quote = not quote
        if not quote:
            if character in "([{":
                depth += 1
            elif character in ")]}":
                depth -= 1
            elif character == "," and depth == 0:
                parts.append("".join(current))
                current = []
                continue
        current.append(character)
    parts.append("".join(current))
    return [part.strip() for part in parts if part.strip()]


def required_parameters(body: str) -> list[str]:
    names = []
    for parameter in split_top_level(strip_annotations(body)):
        match = re.match(r"(?:val|var)\s+(\w+)\s*:", parameter)
        if not match:
            continue
        # A default is an "=" outside any brackets of the type itself.
        without_type = re.sub(r"<[^<>]*>", "", parameter)
        if "=" in without_type.split(":", 1)[-1]:
            continue
        names.append(match.group(1))
    return names


def package_of(text: str) -> str:
    match = re.search(r"^package\s+([\w.]+)", text, re.MULTILINE)
    return match.group(1) if match else ""


def declared_classes() -> dict[str, tuple[str, list[str]]]:
    """Each data class by name, with the package it lives in."""
    classes: dict[str, tuple[str, list[str]]] = {}
    for path in MAIN.rglob("*.kt"):
        text = path.read_text(encoding="utf-8")
        package = package_of(text)
        for match in DECLARATION.finditer(text):
            body, _ = balanced(text, match.end() - 1)
            classes[match.group(1)] = (package, required_parameters(body))
    return classes


def resolves_here(name: str, package: str, text: str, file_package: str) -> bool:
    """Whether `name` in this file means the class declared in [package].

    Compose declares a Row and a Text, and so does this project. Without this
    every `Row {` in the UI is checked against a statement row and reported as
    missing five arguments it was never meant to have.
    """
    if package == file_package:
        return True
    return re.search(rf"^import {re.escape(package)}\.{name}$", text, re.MULTILINE) is not None


def main() -> int:
    classes = declared_classes()
    if not classes:
        print("no data classes found - is this the right directory?")
        return 1

    failures = []
    checked = 0
    for source in SOURCES:
        for path in source.rglob("*.kt"):
            text = path.read_text(encoding="utf-8")
            file_package = package_of(text)
            for name, (package, required) in classes.items():
                if not required:
                    continue
                if not resolves_here(name, package, text, file_package):
                    continue
                for match in re.finditer(rf"(?<![\w.]){name}\s*\(", text):
                    # A declaration, not a construction.
                    prefix = text[max(0, match.start() - 12) : match.start()]
                    if prefix.rstrip().endswith("class"):
                        continue
                    body, _ = balanced(text, match.end() - 1)
                    arguments = split_top_level(body)
                    if not arguments:
                        continue
                    named = {
                        argument.split("=", 1)[0].strip()
                        for argument in arguments
                        if re.match(r"^\w+\s*=(?!=)", argument)
                    }
                    if len(named) != len(arguments):
                        continue  # positional somewhere; leave it to the compiler
                    checked += 1
                    missing = [
                        parameter for parameter in required if parameter not in named
                    ]
                    if missing:
                        line = text[: match.start()].count("\n") + 1
                        failures.append(
                            f"{path.relative_to(ROOT)}:{line} {name} is missing "
                            f"{', '.join(missing)}",
                        )

    for failure in failures:
        print(failure)
    print(
        f"\n{len(classes)} data classes, {checked} fully-named constructions checked, "
        f"{len(failures)} incomplete",
    )
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
