# Batching

*Run a flow many times with one setting varied each time — or two, as a grid.*

Use it to try eight seeds of the same prompt, find the right CFG, or compare step counts
side by side.

## Steps

1. Open the flow and tap the generator node.
2. Beside **Seed**, **Steps**, **CFG**, **Denoise** or **Scheduler** there is a small
   **bars icon**. Tap it: the **Batch** dialog opens with that setting chosen under
   **what varies**.
3. Type the values to try under **values**:

    | you type | runs |
    |---|---|
    | `1, 2, 3` | exactly those |
    | `1..8` | 1 to 8 |
    | `1..10 by 3` | 1, 4, 7, 10 |

    Underneath, the dialog shows how many runs that makes and the values it read.
4. Tap **Run N** (N is the number of runs). The setting's icon turns violet while it is armed.

Arm **two** settings and the batch runs every combination — a grid, for example 3 CFG values ×
4 step counts = 12 pictures.

While it runs, the bar shows `batch 3 of 12`. **Stop after this one** finishes the current
picture and stops.

## Where the pictures go

Every run is kept in **Results**, grouped as one card, each picture with the exact flow that
made it — so the one you like can be reopened with its settings and seed.
