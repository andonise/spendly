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
- **Holdout set:** different merchants for the same categories. It is kept out of the repository until the
  Phase 3 result has been measured, then added here as `categorization-holdout.csv`.

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
