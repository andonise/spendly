"""Regenerate the synthetic sample statements in samples/.

All people, addresses, IBANs and card numbers are fictional. IBANs use the made-up bank codes
SPND/FAKE/TEST but carry valid mod-97 check digits, and card numbers are standard Luhn-valid test
numbers, so the sanitizer treats them as real.

Usage (from the repository root):
    uv run --no-project --with reportlab==4.2.5 python samples/tools/generate_samples.py
"""

from decimal import Decimal
from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle
from reportlab import rl_config

SAMPLES = Path(__file__).resolve().parent.parent

HOLDER = "MARIA EXEMPLU"
ADDRESS = "Str. Exemplului nr. 1, Iasi"
IBAN_CURRENT = "RO54SPND0000111122223333"   # own current account (generic bank)
IBAN_REVOLUT = "RO32FAKE0000444455556666"   # own Revolut account
IBAN_CARD = "RO45SPND0000777788889999"      # own credit card account
IBAN_LANDLORD = "RO38TEST7777888899990000"  # third party
PAN_DEBIT = "4111 1111 1111 1111"
PAN_CREDIT = "4000 0566 5566 5556"


def ro(amount: Decimal) -> str:
    """Romanian number format: 1.234,56"""
    sign = "-" if amount < 0 else ""
    whole, frac = f"{abs(amount):.2f}".split(".")
    groups = []
    while whole:
        groups.insert(0, whole[-3:])
        whole = whole[:-3]
    return f"{sign}{'.'.join(groups)},{frac}"


def spaced(iban: str) -> str:
    return " ".join(iban[i:i + 4] for i in range(0, len(iban), 4))


# --------------------------------------------------------------------------- generic bank CSV
GENERIC_OPENING = Decimal("5000.00")
GENERIC_ROWS = [  # (date, description, signed amount)
    ("01.08.2026", "INCASARE SALARIU ACME SOFTWARE SRL", Decimal("8500.00")),
    ("02.08.2026", f"PLATA CARD {PAN_DEBIT} KAUFLAND 3412 IASI RO", Decimal("-187.43")),
    ("03.08.2026", "PLATA CARD ****1111 STARBUCKS PALAS IASI", Decimal("-12.50")),
    ("03.08.2026", "PLATA CARD ****1111 STARBUCKS PALAS IASI", Decimal("-12.50")),
    ("05.08.2026", f"TRANSFER CATRE ION POPESCU {IBAN_LANDLORD} chirie august", Decimal("-2200.00")),
    ("07.08.2026", "PLATA FACTURA ENEL ENERGIE MUNTENIA", Decimal("-245.10")),
    ("10.08.2026", f"TRANSFER CATRE REVOLUT {IBAN_REVOLUT}", Decimal("-500.00")),
    ("12.08.2026", "PLATA CARD ****1111 NETFLIX.COM AMSTERDAM", Decimal("-55.99")),
    ("14.08.2026", "PLATA CARD ****1111 OMV PETROM 2231 IASI", Decimal("-312.75")),
    ("15.08.2026", f"PLATA CARD CREDIT {IBAN_CARD}", Decimal("-800.00")),
    ("18.08.2026", "RETRAGERE NUMERAR ATM SPENDLY BANK IASI", Decimal("-400.00")),
    ("20.08.2026", "PLATA CARD ****1111 LIDL DISCOUNT IASI", Decimal("-96.40")),
    ("21.08.2026", "PLATA CARD ****1111 LIBRĂRIA CĂRTUREȘTI IAȘI", Decimal("-89.90")),
    ("22.08.2026", "Reincarcare 0722 123 456 Orange", Decimal("-30.00")),
    ("25.08.2026", "COMISION ADMINISTRARE CONT", Decimal("-15.00")),
    ("28.08.2026", "PLATA CARD ****1111 EMAG.RO MARKETPLACE", Decimal("-1249.99")),
    ("30.08.2026", "TRANSFER DE LA MARIA ELENA IONESCU", Decimal("150.00")),
    ("31.08.2026", f"PLATA CARD {PAN_DEBIT} TRATTORIA IL CALCIO IASI", Decimal("-186.00")),
]


def write_generic_csv() -> dict:
    balance = GENERIC_OPENING
    lines = [
        "Extras de cont;;;;;",
        f"Titular;{HOLDER};;;;",
        f"Adresa;{ADDRESS};;;;",
        f"IBAN;{spaced(IBAN_CURRENT)};;;;",
        "Moneda;RON;;;;",
        "Perioada;01.08.2026 - 31.08.2026;;;;",
        f"Sold initial;{ro(GENERIC_OPENING)};;;;",
        ";;;;;",
        "Data tranzactie;Data valuta;Descriere;Debit;Credit;Sold",
    ]
    for date, desc, amount in GENERIC_ROWS:
        balance += amount
        debit = ro(-amount) if amount < 0 else ""
        credit = ro(amount) if amount > 0 else ""
        lines.append(f"{date};{date};{desc};{debit};{credit};{ro(balance)}")
    lines += [";;;;;", f"Sold final;{ro(balance)};;;;"]
    (SAMPLES / "generic-bank-2026-08.csv").write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    return summary("generic-bank-2026-08.csv", GENERIC_OPENING, [a for _, _, a in GENERIC_ROWS], balance)


# --------------------------------------------------------------------------- Revolut CSV
REVOLUT_OPENING = Decimal("250.00")
REVOLUT_ROWS = [  # (type, started, completed, description, amount, fee, state)
    ("TOPUP", "2026-08-11 09:14:22", "2026-08-11 09:14:25", "Top-Up by *3333", Decimal("500.00"), Decimal("0.00"), "COMPLETED"),
    ("CARD_PAYMENT", "2026-08-12 18:02:10", "2026-08-13 10:11:45", "Kaufland", Decimal("-120.00"), Decimal("0.00"), "COMPLETED"),
    ("CARD_PAYMENT", "2026-08-14 07:45:00", "2026-08-15 09:00:12", "Spotify", Decimal("-26.99"), Decimal("0.00"), "COMPLETED"),
    ("CARD_PAYMENT", "2026-08-16 22:31:54", "2026-08-17 08:40:03", "Bolt", Decimal("-18.40"), Decimal("0.00"), "COMPLETED"),
    ("TRANSFER", "2026-08-18 12:00:00", "2026-08-18 12:00:02", "To Ion Popescu", Decimal("-100.00"), Decimal("0.00"), "COMPLETED"),
    ("CARD_PAYMENT", "2026-08-19 20:10:33", "", "Uber", Decimal("-35.20"), Decimal("0.00"), "REVERTED"),
    ("ATM", "2026-08-21 11:05:41", "2026-08-21 11:05:41", "Cash at Revolut ATM", Decimal("-200.00"), Decimal("5.00"), "COMPLETED"),
    ("CARD_PAYMENT", "2026-08-27 17:22:09", "2026-08-28 09:14:50", "Mega Image", Decimal("-64.15"), Decimal("0.00"), "COMPLETED"),
]


def write_revolut_csv() -> dict:
    balance = REVOLUT_OPENING
    lines = ["Type,Product,Started Date,Completed Date,Description,Amount,Fee,Currency,State,Balance"]
    effective = []
    for typ, started, completed, desc, amount, fee, state in REVOLUT_ROWS:
        if state == "COMPLETED":
            balance += amount - fee
            effective.append(amount)
            if fee:
                effective.append(-fee)
            bal = f"{balance:.2f}"
        else:
            bal = ""
        lines.append(f"{typ},Current,{started},{completed},{desc},{amount:.2f},{fee:.2f},RON,{state},{bal}")
    (SAMPLES / "revolut-2026-08.csv").write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    return summary("revolut-2026-08.csv", REVOLUT_OPENING, effective, balance)


# --------------------------------------------------------------------------- credit card PDF
CARD_OPENING = Decimal("-1200.00")
CARD_ROWS = [  # (date, description, signed amount)
    ("03.08.2026", f"PLATA CARD {PAN_CREDIT} ALTEX IASI", Decimal("-450.00")),
    ("09.08.2026", "PLATA CARD ****5556 DECATHLON IASI", Decimal("-350.00")),
    ("16.08.2026", f"RAMBURSARE DIN CONT {IBAN_CURRENT}", Decimal("800.00")),
    ("25.08.2026", "DOBANDA CARD CREDIT", Decimal("-146.00")),
    ("28.08.2026", "COMISION EMITERE EXTRAS", Decimal("-5.00")),
]


def write_card_pdf() -> dict:
    rl_config.invariant = 1  # reproducible bytes (no timestamps / random IDs)
    closing = CARD_OPENING + sum(a for _, _, a in CARD_ROWS)
    total_debit = sum(-a for _, _, a in CARD_ROWS if a < 0)
    total_credit = sum(a for _, _, a in CARD_ROWS if a > 0)
    styles = getSampleStyleSheet()
    out = SAMPLES / "credit-card-2026-08.pdf"
    doc = SimpleDocTemplate(str(out), pagesize=A4, leftMargin=18 * mm, rightMargin=18 * mm,
                            topMargin=18 * mm, bottomMargin=18 * mm,
                            title="Extras card de credit", author="Spendly Demo Bank", creator="spendly samples")
    header = [
        Paragraph("<b>SPENDLY DEMO BANK</b> - Extras de cont card de credit", styles["Title"]),
        Paragraph(f"Titular: {HOLDER}", styles["Normal"]),
        Paragraph(f"Adresa: {ADDRESS}", styles["Normal"]),
        Paragraph(f"IBAN: {spaced(IBAN_CARD)}", styles["Normal"]),
        Paragraph(f"Card: {PAN_CREDIT}", styles["Normal"]),
        Paragraph("Moneda: RON", styles["Normal"]),
        Paragraph("Perioada: 01.08.2026 - 31.08.2026", styles["Normal"]),
        Spacer(1, 6 * mm),
        Paragraph(f"Sold initial: {ro(CARD_OPENING)} RON", styles["Normal"]),
        Spacer(1, 4 * mm),
    ]
    data = [["Data", "Descriere", "Debit", "Credit"]]
    for date, desc, amount in CARD_ROWS:
        data.append([date, desc, ro(-amount) if amount < 0 else "", ro(amount) if amount > 0 else ""])
    data.append(["", "Total", ro(total_debit), ro(total_credit)])
    table = Table(data, colWidths=[24 * mm, 104 * mm, 23 * mm, 23 * mm], repeatRows=1)
    table.setStyle(TableStyle([
        ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
        ("FONTNAME", (0, -1), (-1, -1), "Helvetica-Bold"),
        ("FONTSIZE", (0, 0), (-1, -1), 8.5),
        ("GRID", (0, 0), (-1, -1), 0.4, colors.grey),
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#e8e8e8")),
        ("ALIGN", (2, 0), (3, -1), "RIGHT"),
    ]))
    footer = [Spacer(1, 4 * mm), Paragraph(f"Sold final: {ro(closing)} RON", styles["Normal"]),
              Spacer(1, 8 * mm), Paragraph("Document generat automat. Date fictive, doar pentru teste.", styles["Italic"])]
    doc.build(header + [table] + footer)
    return summary("credit-card-2026-08.pdf", CARD_OPENING, [a for _, _, a in CARD_ROWS], closing)


def summary(name, opening, amounts, closing) -> dict:
    assert opening + sum(amounts) == closing, name
    return {
        "file": name,
        "transactions": len(amounts),
        "debits": f"{sum(-a for a in amounts if a < 0):.2f}",
        "credits": f"{sum(a for a in amounts if a > 0):.2f}",
        "opening": f"{opening:.2f}",
        "closing": f"{closing:.2f}",
    }


if __name__ == "__main__":
    for s in (write_generic_csv(), write_revolut_csv(), write_card_pdf()):
        print(s)
