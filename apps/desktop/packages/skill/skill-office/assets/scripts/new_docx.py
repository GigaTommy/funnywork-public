"""Create a new Word document with explicit fonts instead of template Calibri.

Use only for new documents. Existing customer documents must retain their styles.
Font installation and glyph availability must be checked by the caller/rendering.
"""
from pathlib import Path
import argparse
from docx import Document
from docx.oxml.ns import qn


def new_document(latin, east_asian, monospace=None):
    """Return a blank Document whose styles and theme use the selected families."""
    for name in [latin, east_asian, monospace or latin]:
        if not isinstance(name, str) or not name.strip():
            raise ValueError("Font family names must be nonblank")
    document = Document()
    for style in document.styles:
        fonts = style.element.get_or_add_rPr().get_or_add_rFonts()
        family = (monospace or latin) if style.style_id in {"MacroText", "MacroTextChar"} else latin
        for key in list(fonts.attrib):
            if key.endswith("Theme"):
                del fonts.attrib[key]
        for key in ["ascii", "hAnsi", "cs"]:
            fonts.set(qn("w:" + key), family)
        fonts.set(qn("w:eastAsia"), east_asian)
    # Theme-owned runs can be added after creation; keep their Latin/CJK defaults aligned.
    from lxml import etree
    for part in document.part.package.parts:
        if part.partname.endswith("/theme1.xml"):
            tree = etree.fromstring(part.blob)
            for kind, family in [("latin", latin), ("ea", east_asian), ("cs", latin)]:
                for node in tree.findall(".//" + qn("a:" + kind)):
                    node.set("typeface", family)
            part._blob = etree.tostring(tree, xml_declaration=True, encoding="UTF-8", standalone=True)
    return document


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--latin", required=True)
    parser.add_argument("--east-asian", required=True)
    parser.add_argument("--monospace")
    args = parser.parse_args()
    document = new_document(args.latin, args.east_asian, args.monospace)
    # Exclusive creation protects an existing document, including through a symlink.
    with args.output.open("xb") as stream:
        document.save(stream)


if __name__ == "__main__":
    main()
