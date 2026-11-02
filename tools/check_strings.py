#!/usr/bin/env python3
"""Verifica se as traduções do MystMusic estão completas e consistentes.

Regras (fase 20 em diante):
  * toda string traduzível de values/ existe em values-en/ e values-es/;
  * não existem chaves órfãs nas traduções;
  * os marcadores de formatação (%1$s, %2$d, %%, ...) são iguais aos originais;
  * os plurals têm as mesmas quantidades e os mesmos marcadores;
  * nenhuma tradução está vazia.

Sem dependências externas: roda em qualquer runner com Python 3.
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"
BASE = RES / "values" / "strings.xml"
TRANSLATIONS = [
    ("en", RES / "values-en" / "strings.xml"),
    ("es", RES / "values-es" / "strings.xml"),
]

PLACEHOLDER = re.compile(r"%(?:\d+\$[a-zA-Z]|%)")
NON_POSITIONAL = re.compile(r"%(?!\d+\$|%)[a-zA-Z]")


def load(path: Path) -> dict[str, tuple[str, bool, list[tuple[str, str]]]]:
    root = ET.parse(path).getroot()
    entries: dict[str, tuple[str, bool, list[tuple[str, str]]]] = {}
    for node in root:
        name = node.get("name")
        if not name:
            continue
        if node.tag == "string":
            translatable = node.get("translatable", "true") != "false"
            entries[name] = (node.text or "", translatable, [])
        elif node.tag == "plurals":
            translatable = node.get("translatable", "true") != "false"
            items = [(item.get("quantity", ""), item.text or "") for item in node]
            entries[name] = ("", translatable, items)
    return entries


def placeholders(text: str) -> list[str]:
    return sorted(PLACEHOLDER.findall(text))


def check(name: str, base: dict, translated: dict) -> list[str]:
    errors: list[str] = []
    expected = {key for key, value in base.items() if value[1]}

    missing = sorted(expected - translated.keys())
    extra = sorted(translated.keys() - expected)
    if missing:
        errors.append(f"[{name}] chaves faltando ({len(missing)}): {', '.join(missing)}")
    if extra:
        errors.append(f"[{name}] chaves órfãs ({len(extra)}): {', '.join(extra)}")

    for key in sorted(expected & translated.keys()):
        source_text, _, source_plurals = base[key]
        target_text, _, target_plurals = translated[key]

        if source_plurals:
            source_q = [quantity for quantity, _ in source_plurals]
            target_q = [quantity for quantity, _ in target_plurals]
            if source_q != target_q:
                errors.append(
                    f"[{name}] {key}: quantidades {target_q} != {source_q}"
                )
            for (_, source_item), (_, target_item) in zip(source_plurals, target_plurals):
                if not target_item.strip():
                    errors.append(f"[{name}] {key}: plural vazio")
                if placeholders(source_item) != placeholders(target_item):
                    errors.append(
                        f"[{name}] {key}: marcadores {placeholders(target_item)} "
                        f"!= {placeholders(source_item)}"
                    )
        else:
            if not target_text.strip():
                errors.append(f"[{name}] {key}: texto vazio")
            if placeholders(source_text) != placeholders(target_text):
                errors.append(
                    f"[{name}] {key}: marcadores {placeholders(target_text)} "
                    f"!= {placeholders(source_text)}"
                )
            if NON_POSITIONAL.search(target_text):
                errors.append(
                    f"[{name}] {key}: posicional não numerado; use %1$s/%2$d"
                )
    return errors


def main() -> int:
    if not BASE.exists():
        print(f"Arquivo base não encontrado: {BASE}", file=sys.stderr)
        return 1

    base = load(BASE)
    print(f"values: {len(base)} recursos ({sum(1 for v in base.values() if v[1])} traduzíveis)")

    failures: list[str] = []
    for name, path in TRANSLATIONS:
        if not path.exists():
            failures.append(f"[{name}] arquivo ausente: {path}")
            continue
        translated = load(path)
        print(f"{name}: {len(translated)} recursos em {path.relative_to(ROOT)}")
        failures.extend(check(name, base, translated))

    if failures:
        print("\nFalhas de tradução:\n", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        return 1

    print("Traduções completas e consistentes.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
