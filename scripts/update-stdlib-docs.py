#!/usr/bin/env python3
"""Regenerate src/main/resources/stdlib/jsonnet-stdlib-docs.txt from the official Jsonnet stdlib reference.

  scripts/update-stdlib-docs.py                  # downloads https://jsonnet.org/ref/stdlib.html
  scripts/update-stdlib-docs.py --file page.html # or use a saved copy

The page is CC BY 2.5 (see src/main/resources/META-INF/THIRD_PARTY_NOTICES.md), so the output keeps the
authors' text and marks what was changed:
  * markup reduced to p / em / code / a / ul / li / pre (whatever else the page uses is unwrapped),
    relative links made absolute;
  * the "Available since version X" line becomes its own field (shown last in the hover);
  * one-line "Example: ... yields ..." paragraphs are omitted - the hover shows a snippet whose result
    StdLibExamplesTest evaluates instead. Multi-line <pre> examples and everything else are kept.

Only the per-function entries (<h4 id="std-NAME">) are taken. The math / type-predicate sections list
their functions without any description, and nothing is invented for them.

Output format, read by StdLibDocs.kt: lines before the first "== " are comments; each entry is
"== <name> | <since or empty> | <anchor on the page>" followed by an HTML fragment (which may span lines, because of <pre>).
"""
import argparse, html, os, re, sys, textwrap, urllib.request
from html.parser import HTMLParser

URL = "https://jsonnet.org/ref/stdlib.html"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "src/main/resources/stdlib/jsonnet-stdlib-docs.txt")

# The page's own spelling differs from the function's (`std.escapeStringXML` in sjsonnet and go-jsonnet).
NAME_ALIASES = {"escapeStringXml": "escapeStringXML"}

KEEP = {"p", "em", "code", "a", "ul", "li", "pre"}
RENAME = {"tt": "code", "b": "em", "strong": "em"}


class Sanitizer(HTMLParser):
    """Turns one entry's markup into a whitelisted fragment; tolerant of the page's stray closing tags."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.out = []
        self.open = []  # whitelisted tags currently open, so stray closers can be ignored

    def handle_starttag(self, tag, attrs):
        tag = RENAME.get(tag, tag)
        if tag not in KEEP:
            return
        if tag in ("pre", "ul") and "p" in self.open:  # the page nests these inside <p>; that isn't valid HTML
            self.handle_endtag("p")
        self.open.append(tag)
        if tag == "a":
            href = dict(attrs).get("href", "")
            if href.startswith("/"):
                href = "https://jsonnet.org" + href
            elif href.startswith("#"):
                href = URL + href
            self.out.append('<a href="%s">' % html.escape(href, quote=True))
        else:
            self.out.append("<%s>" % tag)

    def handle_endtag(self, tag):
        tag = RENAME.get(tag, tag)
        if tag in KEEP and tag in self.open:
            while self.open:
                top = self.open.pop()
                self.out.append("</%s>" % top)
                if top == tag:
                    break

    def handle_data(self, data):
        self.out.append(html.escape(data, quote=False))

    def result(self):
        while self.open:
            self.out.append("</%s>" % self.open.pop())
        return "".join(self.out)


def tidy(fragment):
    """Collapse whitespace outside <pre>, drop empty paragraphs, one block per line."""
    def prose(p):
        p = re.sub(r"\s+", " ", p)
        return re.sub(r" ?(</?(?:p|ul|li)>) ?", r"\1", p)

    def pre(p):
        body = p[len("<pre>"):-len("</pre>")]
        if body.startswith("\n"):  # the page indents these blocks with the surrounding markup
            body = textwrap.dedent(body.lstrip("\n")).rstrip()
        return "<pre>" + body + "</pre>"

    pieces = re.split(r"(<pre>.*?</pre>)", fragment, flags=re.S)
    text = "".join(pre(p) if p.startswith("<pre>") else prose(p) for p in pieces)
    text = re.sub(r"<p></p>", "", text)
    text = re.sub(r"(</p>|</ul>|</pre>)", r"\1\n", text)
    return re.sub(r"\n+", "\n", text).strip()


def plain(fragment):
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", "", fragment))).strip()


def entries(page):
    body = page[page.index('id="standard_library"'):]
    for chunk in re.split(r"(?=<h[34] id=)", body):
        m = re.match(r'<h4 id="std-([^"]+)">(.*?)</h4>(.*)', chunk, re.S)
        if m:
            yield m.group(1), m.group(3)


def convert(markup):
    paragraphs = re.findall(r"<(?:p|ul|pre)\b.*?</(?:p|ul|pre)>", markup, re.S)
    since, kept = "", []
    for block in paragraphs:
        s = Sanitizer()
        s.feed(block)
        s.close()
        fragment = s.result()
        text = plain(fragment)
        available = re.fullmatch(r"Available since version ([0-9.]+?)\.?", text)
        if available and not since:
            since = available.group(1)
        elif fragment.startswith("<p>") and re.match(r"Examples?:", text):
            continue
        else:
            kept.append(fragment)
    return since, tidy("".join(kept))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--file", help="read a saved copy of the page instead of downloading it")
    args = ap.parse_args()
    if args.file:
        page = open(args.file, encoding="utf-8").read()
    else:
        with urllib.request.urlopen(URL, timeout=30) as response:
            page = response.read().decode("utf-8")

    lines = [
        "# Descriptions from the Jsonnet standard library reference, " + URL,
        "# Copyright the Jsonnet authors, licensed CC BY 2.5 (https://creativecommons.org/licenses/by/2.5/).",
        "# Adapted: markup simplified, 'Available since' split out, one-line 'Example:' paragraphs omitted.",
        "# GENERATED by scripts/update-stdlib-docs.py - do not edit by hand.",
    ]
    count = 0
    for raw_name, markup in entries(page):
        name = NAME_ALIASES.get(raw_name, raw_name)
        since, fragment = convert(markup)
        if not fragment:
            continue
        assert not any(l.startswith("== ") for l in fragment.split("\n")), name
        lines.append("== %s | %s | std-%s" % (name, since, raw_name))
        lines.append(fragment)
        count += 1

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("wrote %d entries to %s" % (count, os.path.relpath(OUT, ROOT)), file=sys.stderr)


if __name__ == "__main__":
    main()
