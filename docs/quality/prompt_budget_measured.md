# Prompt budget — before vs after (ESTIMATED tokens, 3 chars/token; window=4096, reserve=1024)

Requests: 133 (dev 88, holdout 45); fixtures: history of 0/3/8/15 earlier exchanges, with and without 3 personal notes.

| history (exchanges) | before: avg / max | after: avg / max | before: headroom<reserve | after: headroom<reserve |
|---|---|---|---|---|
| 0 | 1280 / 1328 | 590 / 637 | 0/133 | 0/133 |
| 3 | 1650 / 1717 | 985 / 1055 | 0/133 | 0/133 |
| 8 | 2204 / 2323 | 1251 / 1330 | 0/133 | 0/133 |
| 15 | 2204 / 2317 | 1251 / 1335 | 0/133 | 0/133 |

Base system block (no notes, no history): before 3497 chars / ~1227 tokens; after 1522 chars / ~539 tokens.
Live conversation (60 turns, ~480-char replies): before — reply reserve is eaten at turn 10 and nothing prevents overflow; after — the conversation is rotated 4 times to stay within the prompt ceiling.
