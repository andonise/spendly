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


# --------------------------------------------------------------------------- positioned-text PDFs
# Real bank PDFs are mostly positioned text without ruling lines. These samples mimic that.

def en(amount: Decimal) -> str:
    """English number format: 1,234.56"""
    return f"{abs(amount):,.2f}"


def _canvas(name: str, title: str):
    from reportlab.pdfgen import canvas

    rl_config.invariant = 1
    c = canvas.Canvas(str(SAMPLES / name), pagesize=A4, invariant=1)
    c.setTitle(title)
    c.setAuthor("spendly samples")
    return c


REVOLUT_PDF_OPENING = Decimal("320.50")
REVOLUT_PDF_ROWS = [  # (date, description, signed amount, detail lines)
    ("Sep 1, 2026", "Apple Pay top-up by *3333", Decimal("400.00"), ["Transaction Id: 0b1c0000-0000-4000-8000-000000000001", spaced(IBAN_REVOLUT), "From: *3333"]),
    ("Sep 2, 2026", "Lidl", Decimal("-87.45"), ["Transaction Id: 0b1c0000-0000-4000-8000-000000000002", "To: Lidl Discount Srl, Iasi", "Card: 411111******1111"]),
    ("Sep 3, 2026", "Spotify", Decimal("-26.99"), ["Transaction Id: 0b1c0000-0000-4000-8000-000000000003", "To: Spotify, Stockholm", "Card: 411111******1111"]),
    ("Sep 5, 2026", "Transfer from Ion Popescu", Decimal("150.00"), ["Reference: Pizza", "Transaction Id: 0b1c0000-0000-4000-8000-000000000004", spaced(IBAN_LANDLORD)]),
    ("Sep 8, 2026", "Bolt", Decimal("-18.40"), ["Transaction Id: 0b1c0000-0000-4000-8000-000000000005", "To: Bolt Operations, Tallinn", "Card: 411111******1111"]),
    ("Sep 12, 2026", "Netflix", Decimal("-55.99"), ["Revolut Rate 1.00 RON = €0.20 (ECB rate* 1.00 RON = €0.20) €11.20", "Card: 411111******1111"]),
    ("Sep 15, 2026", "Mega Image", Decimal("-164.15"), ["To: Mega Image 0042, Iasi", "Card: 411111******1111"]),
    ("Sep 20, 2026", "Freshful", Decimal("-212.30"), ["To: Freshful, Bucuresti", "Card: 411111******1111"]),
    ("Sep 25, 2026", "Transfer from MARIA EXEMPLU", Decimal("900.00"), [spaced(IBAN_CURRENT)]),
    ("Sep 28, 2026", "Kaufland", Decimal("-143.72"), ["To: Kaufland 3412, Iasi", "Card: 411111******1111"]),
    ("Sep 29, 2026", "Catena", Decimal("-39.90"), ["To: Catena 12, Cluj-Napoca", "Card: 411111******1111"]),
    ("Sep 30, 2026", "Tazz", Decimal("-61.50"), ["To: Tazz, Bucuresti", "Card: 411111******1111"]),
]
REVOLUT_PDF_PENDING = [("Sep 30, 2026", "Emag", Decimal("-499.99"))]
REVOLUT_PDF_REVERTED = [("Sep 9, 2026", "Uber", Decimal("-35.20"))]


def write_revolut_pdf() -> dict:
    """Revolut-style statement: balance summary, Pending, Account transactions (2 pages), Reverted."""
    amounts = [a for _, _, a, _ in REVOLUT_PDF_ROWS]
    closing = REVOLUT_PDF_OPENING + sum(amounts)
    out_total = sum(-a for a in amounts if a < 0)
    in_total = sum(a for a in amounts if a > 0)
    c = _canvas("revolut-2026-09.pdf", "Revolut statement (synthetic)")
    width, height = A4
    X_DATE, X_DESC, X_OUT, X_IN, X_BAL = 43, 125, 380, 460, 555

    def top(page_title: bool = True) -> float:
        c.setFont("Helvetica-Bold", 14)
        c.drawString(X_DATE, height - 50, "Personal Account RON Statement")
        c.setFont("Helvetica", 8)
        c.drawString(X_DATE, height - 64, "Generated on the Oct 1, 2026")
        c.drawString(X_DATE, height - 76, "Revolut Bank UAB Vilnius Sucursala Bucuresti")
        return height - 100

    def money(x: float, y: float, amount: Decimal) -> None:
        c.drawRightString(x, y, f"{en(amount)} RON")

    def table_header(y: float, balance: bool = True, first: str = "Date") -> float:
        c.setFont("Helvetica-Bold", 8)
        c.drawString(X_DATE, y, first)
        c.drawString(X_DESC, y, "Description")
        c.drawRightString(X_OUT - 6, y, "Money out")
        c.drawRightString(X_IN - 10, y, "Money in")
        if balance:
            c.drawRightString(X_BAL, y, "Balance")
        c.setFont("Helvetica", 8)
        return y - 16

    y = top()
    c.setFont("Helvetica", 8)
    for line in (HOLDER, ADDRESS, "IBAN " + IBAN_REVOLUT, "BIC REVOROBB"):
        c.drawString(X_DATE, y, line)
        y -= 11
    y -= 8
    c.setFont("Helvetica-Bold", 10)
    c.drawString(X_DATE, y, "Balance summary")
    y -= 16
    c.setFont("Helvetica-Bold", 8)
    c.drawString(X_DATE, y, "Product")
    c.drawString(253, y, "Opening balance")
    c.drawRightString(X_OUT - 6, y, "Money out")
    c.drawRightString(X_IN - 10, y, "Money in")
    c.drawRightString(X_BAL, y, "Closing balance")
    y -= 14
    c.setFont("Helvetica", 8)
    for label in ("Account (Current Account)", "Total"):
        c.drawString(X_DATE, y, label)
        c.drawRightString(330, y, f"{en(REVOLUT_PDF_OPENING)} RON")
        money(X_OUT, y, out_total)
        money(X_IN, y, in_total)
        money(X_BAL, y, closing)
        y -= 12
    y -= 6
    c.drawString(X_DATE, y, "The balance on your statement might differ from the balance shown in your app.")
    y -= 22
    c.setFont("Helvetica-Bold", 10)
    c.drawString(X_DATE, y, "Pending from September 1, 2026 to September 30, 2026")
    y = table_header(y - 16, balance=False, first="Start date")
    for d, desc, a in REVOLUT_PDF_PENDING:
        c.drawString(X_DATE, y, d)
        c.drawString(X_DESC, y, desc)
        money(X_OUT, y, a)
        y -= 11
        c.drawString(X_DESC, y, "Card: 411111******1111")
        y -= 16
    c.setFont("Helvetica-Bold", 10)
    c.drawString(X_DATE, y, "Account transactions from September 1, 2026 to September 30, 2026")
    y = table_header(y - 16)
    balance = REVOLUT_PDF_OPENING
    for i, (d, desc, a, details) in enumerate(REVOLUT_PDF_ROWS):
        if i == 7:  # continue on page 2 with a repeated header
            c.drawString(X_DATE, 60, "Report lost or stolen card. Revolut Bank UAB is authorised by the Bank of Lithuania (synthetic footer).")
            c.showPage()
            y = table_header(top())
        balance += a
        c.drawString(X_DATE, y, d)
        c.drawString(X_DESC, y, desc)
        money(X_OUT if a < 0 else X_IN, y, a)
        money(X_BAL, y, balance)
        y -= 11
        for detail in details:
            c.drawString(X_DESC, y, detail)
            y -= 11
        y -= 5
    y -= 10
    c.setFont("Helvetica-Bold", 10)
    c.drawString(X_DATE, y, "Reverted from September 1, 2026 to September 30, 2026")
    y = table_header(y - 16, balance=False, first="Start date")
    for d, desc, a in REVOLUT_PDF_REVERTED:
        c.drawString(X_DATE, y, d)
        c.drawString(X_DESC, y, desc)
        money(X_OUT, y, a)
        y -= 16
    c.drawString(X_DATE, 60, "Report lost or stolen card. Synthetic document, test data only.")
    c.save()
    return summary("revolut-2026-09.pdf", REVOLUT_PDF_OPENING, amounts, closing)


OTHER_BANK_ROWS = [  # chronological (date dd/MM/yyyy, description lines, signed amount); printed newest first
    ("01/09/2026", ["CARD PAYMENT TESCO STORES 2231", "LONDON GB"], Decimal("-54.20")),
    ("02/09/2026", ["DIRECT DEBIT OCTOPUS ENERGY"], Decimal("-92.00")),
    ("03/09/2026", ["FASTER PAYMENT FROM ION POPESCU", "REF FLAT SHARE SEPT"], Decimal("450.00")),
    ("10/09/2026", ["CARD PAYMENT PRET A MANGER"], Decimal("-8.95")),
    ("15/09/2026", ["SALARY SPENDLY DEMO LTD"], Decimal("2,350.00".replace(",", ""))),
    ("15/09/2026", ["CARD PAYMENT PRET A MANGER"], Decimal("-8.95")),
    ("22/09/2026", ["STANDING ORDER TO LANDLORD", "RO38 TEST 7777 8888 9999 0000"], Decimal("-1,100.00".replace(",", ""))),
    ("29/09/2026", ["CASH WITHDRAWAL ATM 0042"], Decimal("-100.00")),
]
OTHER_BANK_OPENING = Decimal("812.40")


def write_other_bank_pdf() -> dict:
    """English bank: Date | Details | Paid out | Paid in | Balance, newest first, dd/MM/yyyy, wrapped
    details, no opening/closing lines (only the running balance), two pages."""
    amounts = [a for _, _, a in OTHER_BANK_ROWS]
    balances = []
    running = OTHER_BANK_OPENING
    for a in amounts:
        running += a
        balances.append(running)
    closing = running
    c = _canvas("other-bank-2026-09.pdf", "Other bank statement (synthetic)")
    width, height = A4
    X_DATE, X_DESC, X_OUT, X_IN, X_BAL = 50, 120, 400, 470, 545

    def page_top() -> float:
        c.setFont("Helvetica-Bold", 13)
        c.drawString(X_DATE, height - 50, "Example Bank plc - Current account statement")
        c.setFont("Helvetica", 9)
        c.drawString(X_DATE, height - 66, f"{HOLDER}, {ADDRESS}")
        c.drawString(X_DATE, height - 78, f"Account {spaced(IBAN_CURRENT)}   Card {PAN_DEBIT}")
        c.drawString(X_DATE, height - 90, "Statement period: 01/09/2026 to 30/09/2026")
        y = height - 120
        c.setFont("Helvetica-Bold", 9)
        c.drawString(X_DATE, y, "Date")
        c.drawString(X_DESC, y, "Details")
        c.drawRightString(X_OUT, y, "Paid out")
        c.drawRightString(X_IN, y, "Paid in")
        c.drawRightString(X_BAL, y, "Balance")
        c.setFont("Helvetica", 9)
        return y - 18

    y = page_top()
    rows = list(zip(OTHER_BANK_ROWS, balances))[::-1]
    for i, ((d, lines, a), bal) in enumerate(rows):
        if i == 5:
            c.drawString(X_DATE, 50, "Example Bank plc is a fictional bank. Page 1 of 2")
            c.showPage()
            y = page_top()
        c.drawString(X_DATE, y, d)
        c.drawString(X_DESC, y, lines[0])
        c.drawRightString(X_OUT if a < 0 else X_IN, y, en(a))
        c.drawRightString(X_BAL, y, en(bal) if bal >= 0 else f"-{en(bal)}")
        for extra in lines[1:]:
            y -= 11
            c.drawString(X_DESC, y, extra)
        y -= 17
    c.drawString(X_DATE, 50, "Example Bank plc is a fictional bank. Page 2 of 2")
    c.save()
    return summary("other-bank-2026-09.pdf", OTHER_BANK_OPENING, amounts, closing)


RO_SIGNED_ROWS = [  # (date dd.MM.yyyy, description, signed amount)
    ("02.09.2026", "POS PROFI 118 IASI", Decimal("-45.30")),
    ("04.09.2026", "INCASARE TRANSFER DE LA ION POPESCU", Decimal("300.00")),
    ("09.09.2026", "PLATA FACTURA E.ON ENERGIE", Decimal("-187.64")),
    ("18.09.2026", "RETRAGERE NUMERAR ATM 0012", Decimal("-200.00")),
    ("27.09.2026", "POS CARREFOUR FELICIA IASI", Decimal("-1.120,49".replace(".", "").replace(",", "."))),
]
RO_SIGNED_OPENING = Decimal("2500.00")


def write_ro_signed_pdf() -> dict:
    """Romanian bank: Data | Detalii tranzactie | Suma (signed) with Sold initial / Sold final lines and no
    balance column."""
    amounts = [a for _, _, a in RO_SIGNED_ROWS]
    closing = RO_SIGNED_OPENING + sum(amounts)
    c = _canvas("ro-bank-2026-09.pdf", "Extras de cont (sintetic)")
    width, height = A4
    c.setFont("Helvetica-Bold", 13)
    c.drawString(50, height - 50, "BANCA EXEMPLU SA - Extras de cont")
    c.setFont("Helvetica", 9)
    for i, line in enumerate((f"Titular: {HOLDER}", f"IBAN: {spaced(IBAN_CURRENT)}", "Perioada: 01.09.2026 - 30.09.2026",
                              "Moneda: RON", f"Sold initial: {ro(RO_SIGNED_OPENING)} RON")):
        c.drawString(50, height - 70 - 12 * i, line)
    y = height - 150
    c.setFont("Helvetica-Bold", 9)
    c.drawString(50, y, "Data")
    c.drawString(130, y, "Detalii tranzactie")
    c.drawRightString(540, y, "Suma")
    c.setFont("Helvetica", 9)
    y -= 18
    for d, desc, a in RO_SIGNED_ROWS:
        c.drawString(50, y, d)
        c.drawString(130, y, desc)
        c.drawRightString(540, y, ro(a))
        y -= 15
    c.drawString(50, y - 10, f"Sold final: {ro(closing)} RON")
    c.save()
    return summary("ro-bank-2026-09.pdf", RO_SIGNED_OPENING, amounts, closing)


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
    for s in (write_generic_csv(), write_revolut_csv(), write_card_pdf(), write_revolut_pdf(),
              write_other_bank_pdf(), write_ro_signed_pdf()):
        print(s)
