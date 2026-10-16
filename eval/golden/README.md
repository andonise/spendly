# Golden datasets (synthetic)

## categorization-dev.csv

Labelled transactions for measuring categorization (plan Phase 3: "at least 90% categorization
accuracy on a labelled sample"). All rows are synthetic.

- Columns: `description,category`. `description` is already **sanitized**, in the form the categorizer
  receives (generic-bank style `PLATA CARD ****1111 <MERCHANT> <store> <CITY> RO`, `POS <MERCHANT> <CITY>`,
  or Revolut/app style `<Merchant>`). `category` is one value of the fixed category enum.
- Internal transfers are not included: transfer detection handles them before categorization.
- **Dev set:** use it freely to develop and tune prompts, thresholds and seed rules. Do **not** hard-code its
  descriptions or merchants into the categorizer, because that would only measure memorization.
- **Holdout set** (`categorization-holdout.csv`, 81 rows): different merchants for the same categories. It
  was kept out of the repository until the Phase 3 result was measured. Now that it is public, tuning
  against it makes it a second dev set: generate a fresh holdout before claiming a new result.

## Metric

Run the categorizer cold, with system seed rules and no user learning (no aliases, hints or examples
from answers), over every row:

| Metric | Definition |
|---|---|
| coverage | rows categorized automatically (not turned into a question) / all rows |
| accuracy | correct automatic categories / rows categorized automatically |
| per layer | coverage and accuracy for RULE, EMBEDDING and LLM separately |

**Target: accuracy ≥ 90%.** A question instead of a guess is correct behaviour ("never guess
silently"), so it lowers coverage, not accuracy. Report coverage next to accuracy, so a high
accuracy reached by asking about everything is visible.

## Results

Phase 3 baseline, 2026-10-16 (`evalCategorization`, qwen3:8b + nomic-embed-text, cold start):

| Set | Coverage | Accuracy | RULE | EMBEDDING | LLM |
|---|---|---|---|---|---|
| dev (111) | 100% | 96.4% | 89 rows, 100% | 0 | 22 rows, 81.8% |
| holdout (81) | 93.8% | **84.2%** (below target) | 28 rows, 100% | 0 | 48 rows, 75.0% |

The dev score mostly reflects the seed rules (well-known chains cover 80% of dev, 35% of holdout); the
holdout measures the LLM layer on unfamiliar merchants. Typical misses: vet clinic → HEALTH (PETS),
PlayStation Store → SUBSCRIPTIONS (ENTERTAINMENT), city public transport → TRAVEL/TAXES/UTILITIES
(TRANSPORT), fuel station → SHOPPING (FUEL). Improving the LLM layer is an open follow-up.
