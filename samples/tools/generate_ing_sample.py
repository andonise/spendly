"""Generate samples/ing-2026-09.pdf: a synthetic statement in the ING Romania "Extras de cont" layout.

Only the layout is modelled on ING (positioned text, no grid; ``Data | Detalii tranzactie | Debit | Credit``;
one line per transaction followed by labelled detail lines; ``Sold initial`` / ``Sold final`` at the end).
Every person, merchant, IBAN, card number and amount is invented (see samples/README.md). Page 2 also has a
"Tranzactii in asteptare" (pending) section, which the parser must skip.

Usage (from the repository root):
    uv run --no-project --with reportlab==4.2.5 python samples/tools/generate_ing_sample.py
"""

from decimal import Decimal
from pathlib import Path

from reportlab import rl_config
from reportlab.pdfgen import canvas

SAMPLES = Path(__file__).resolve().parent.parent
OUT = SAMPLES / "ing-2026-09.pdf"
WIDTH, HEIGHT = 592, 842

HOLDER = "MARIA EXEMPLU"
ADDRESS = "Str. Exemplului nr. 1, Iasi"
IBAN_OWN = "RO54SPND0000111122223333"      # fictional, valid mod-97 (samples/README.md)
IBAN_REVOLUT = "RO32FAKE0000444455556666"  # own Revolut (fictional)
IBAN_LANDLORD = "RO38TEST7777888899990000" # third party (fictional)
PAN = "4111 1111 1111 1111"                # standard test card number
PAN_MASKED = "411111******1111"

OPENING = Decimal("4000.00")
# (day, type, signed amount, detail lines)
ROWS = [
    (1, "Incasare", Decimal("6500.00"), [("Ordonator", "SC EXEMPLU SOFTWARE SRL"), ("Din contul", IBAN_LANDLORD),
                                         ("Detalii", "Salariu august"), ("Referinta", "REF000111")]),
    (2, "Cumparare POS", Decimal("-187.45"), [("Numar card", PAN_MASKED), ("Terminal", "LIDL 1234 IASI RO"),
                                              ("Data autorizarii", "31-08-2026"), ("Numar autorizare", "000111")]),
    (3, "Transfer", Decimal("-2500.00"), [("Beneficiar", "Ion Popescu"), ("In contul", IBAN_LANDLORD),
                                          ("Banca", "TEST BANK"), ("Detalii", "chirie septembrie")]),
    (5, "Cumparare POS", Decimal("-245.10"), [("Numar card", PAN_MASKED), ("Terminal", "PETROM 0567 IASI RO"),
                                              ("Data autorizarii", "04-09-2026"), ("Numar autorizare", "000222")]),
    (8, "Plati cu card de tip transfer", Decimal("-500.00"), [("Numar card", PAN_MASKED),
                                                             ("Terminal", "REVOLUT TOPUP"),
                                                             ("Data autorizarii", "08-09-2026"),
                                                             ("Numar autorizare", "000333")]),
    (12, "Cumparare POS", Decimal("-1249.99"), [("Numar card", PAN_MASKED), ("Terminal", "EMAG.RO BUCURESTI RO"),
                                                ("Data autorizarii", "11-09-2026"), ("Numar autorizare", "000444")]),
    (15, "Cumparare POS", Decimal("-64.00"), [("Numar card", PAN_MASKED), ("Terminal", "CINEMA EXEMPLU IASI RO"),
                                              ("Data autorizarii", "14-09-2026"), ("Numar autorizare", "000555")]),
    (18, "Transfer", Decimal("-300.00"), [("Beneficiar", "MARIA EXEMPLU"), ("In contul", IBAN_REVOLUT),
                                          ("Detalii", "economii")]),
    (22, "Cumparare POS", Decimal("-92.30"), [("Numar card", PAN_MASKED), ("Terminal", "FARMACIA EXEMPLU IASI RO"),
                                              ("Data autorizarii", "21-09-2026"), ("Numar autorizare", "000666")]),
    (25, "Comision", Decimal("-5.00"), [("Detalii", "Comision administrare cont")]),
    (28, "Incasare", Decimal("150.00"), [("Ordonator", "Andrei Exemplu"), ("Din contul", IBAN_REVOLUT),
                                         ("Detalii", "bilet concert")]),
    (30, "Cumparare POS", Decimal("-210.75"), [("Numar card", PAN_MASKED), ("Terminal", "MEGA EXEMPLU 0042 IASI RO"),
                                               ("Data autorizarii", "29-09-2026"), ("Numar autorizare", "000777")]),
]
PENDING = [(30, "Cumparare POS", Decimal("-45.00"), [("Numar card", PAN_MASKED), ("Terminal", "TAXI EXEMPLU IASI RO")])]
ROWS_PER_PAGE = 7


def ro(amount: Decimal) -> str:
    whole, frac = f"{abs(amount):.2f}".split(".")
    groups = []
    while whole:
        groups.insert(0, whole[-3:])
        whole = whole[:-3]
    return f"{'-' if amount < 0 else ''}{'.'.join(groups)},{frac}"


def spaced(iban: str) -> str:
    return " ".join(iban[i:i + 4] for i in range(0, len(iban), 4))


class Page:
    def __init__(self, c: canvas.Canvas) -> None:
        self.c = c

    def text(self, x: float, top: float, s: str, size: float = 8, bold: bool = False, right: bool = False) -> None:
        self.c.setFont("Helvetica-Bold" if bold else "Helvetica", size)
        y = HEIGHT - top - size
        (self.c.drawRightString if right else self.c.drawString)(x, y, s)


def account_block(p: Page, top: float) -> None:
    p.text(20, top, f"Titular cont: {HOLDER}")
    p.text(346, top, "Tip cont: Cont Curent")
    p.text(346, top + 14, f"Numar cont: {spaced(IBAN_OWN)}")
    p.text(346, top + 28, "Moneda RON")


def table_header(p: Page, top: float) -> float:
    p.text(21, top, "Data", bold=True)
    p.text(131, top, "Detalii tranzactie", bold=True)
    p.text(473, top, "Debit", bold=True)
    p.text(538, top, "Credit", bold=True)
    p.c.setLineWidth(0.5)
    p.c.line(20, HEIGHT - top - 12, 572, HEIGHT - top - 12)
    return top + 35


def row(p: Page, top: float, day: int, kind: str, amount: Decimal, details) -> float:
    p.text(20, top, f"{day:02d} septembrie 2026")
    p.text(130, top, kind)
    p.text(502 if amount < 0 else 572, top, ro(abs(amount)), right=True)
    top += 15
    for label, value in details:
        p.text(130, top, f"{label}: {value}")
        top += 12
    return top + 4


def footer(p: Page, page_no: int, pages: int) -> None:
    p.text(21, 747, "ING Bank N.V. Amsterdam - Sucursala Bucuresti", size=7)
    p.text(425, 747, "ING Bank N.V. Amsterdam - Sucursala Bucuresti", size=7)
    p.text(526, 787, f"{page_no}/{pages}", size=7)
    p.text(20, 793, "Document generat automat. Date fictive, doar pentru teste; nu reprezinta un extras real.", size=6)


def main() -> dict:
    rl_config.invariant = 1  # reproducible bytes
    closing = OPENING + sum(a for _, _, a, _ in ROWS)
    chunks = [ROWS[i:i + ROWS_PER_PAGE] for i in range(0, len(ROWS), ROWS_PER_PAGE)]
    c = canvas.Canvas(str(OUT), pagesize=(WIDTH, HEIGHT), invariant=1)
    c.setTitle("Extras de cont")
    c.setAuthor("Spendly samples")
    for n, chunk in enumerate(chunks, start=1):
        p = Page(c)
        if n == 1:
            p.text(509, 31, "Extras de cont", bold=True)
            p.text(420, 50, "Pentru perioada: 01/09/2026-30/09/2026")
            p.text(20, 89, "ING Bank N.V. Amsterdam - Sucursala Bucuresti", bold=True)
            p.text(20, 105, ADDRESS)
            p.text(20, 120, f"Card de debit: {PAN}")
            account_block(p, 160)
            top = table_header(p, 256)
        else:
            account_block(p, 57)
            top = table_header(p, 153)
        for day, kind, amount, details in chunk:
            top = row(p, top, day, kind, amount, details)
        if n == len(chunks):
            top += 10
            p.text(20, top, "Tranzactii in asteptare", bold=True)
            top = table_header(p, top + 18)
            for day, kind, amount, details in PENDING:
                top = row(p, top, day, kind, amount, details)
            p.text(20, top + 8, f"Sold initial: {ro(OPENING)}")
            p.text(20, top + 29, f"Sold final: {ro(closing)}")
        footer(p, n, len(chunks))
        c.showPage()
    c.save()
    amounts = [a for _, _, a, _ in ROWS]
    return {
        "file": OUT.name,
        "transactions": len(amounts),
        "debits": f"{sum(-a for a in amounts if a < 0):.2f}",
        "credits": f"{sum(a for a in amounts if a > 0):.2f}",
        "opening": f"{OPENING:.2f}",
        "closing": f"{closing:.2f}",
    }


if __name__ == "__main__":
    print(main())
