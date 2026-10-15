"""PDF extraction: pick a LayoutParser for the document and return a RawStatement."""

from __future__ import annotations

import io
from typing import BinaryIO

import pdfplumber
from pdfminer.pdfparser import PDFSyntaxError

from .base import ExtractionError, LayoutParser, RawRow, RawStatement
from .ro_card_table import RomanianCardTableLayout

__all__ = [
    "ExtractionError",
    "LayoutParser",
    "RawRow",
    "RawStatement",
    "default_layouts",
    "extract_statement",
]


def default_layouts() -> list[LayoutParser]:
    # Order matters: the first layout whose detect() is True is used. Add docling/camelot/LLM layouts here.
    return [RomanianCardTableLayout()]


def extract_statement(source: bytes | BinaryIO, layouts: list[LayoutParser] | None = None) -> RawStatement:
    stream = io.BytesIO(source) if isinstance(source, bytes) else source
    try:
        pdf = pdfplumber.open(stream)
    except (PDFSyntaxError, ValueError, TypeError, KeyError):
        raise ExtractionError("FILE_UNREADABLE", "The file is not a readable PDF") from None
    except Exception as exc:  # pdfminer raises a variety of types for broken/encrypted files
        if type(exc).__module__.startswith(("pdfminer", "pdfplumber")):
            raise ExtractionError("FILE_UNREADABLE", "The file is not a readable PDF") from None
        raise
    with pdf:
        try:
            if not pdf.pages:
                raise ExtractionError("FILE_UNREADABLE", "The PDF has no pages")
            for layout in layouts if layouts is not None else default_layouts():
                if layout.detect(pdf):
                    return layout.extract(pdf)
        except ExtractionError:
            raise
        except Exception as exc:
            if type(exc).__module__.startswith(("pdfminer", "pdfplumber")):
                raise ExtractionError("FILE_UNREADABLE", "The PDF content could not be read") from None
            raise
    raise ExtractionError("UNSUPPORTED_LAYOUT", "No supported statement layout was recognized")
