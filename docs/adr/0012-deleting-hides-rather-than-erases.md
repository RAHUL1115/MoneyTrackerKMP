# Deleting an account, category, tag or repeat hides it rather than erasing it

These rows carry a `deleted` flag instead of being removed. Past entries still show the name of the account or category they were made with, and a deleted account can be brought back with its history. Lists and pickers filter deleted rows out, while history and reports still look up names by id, deleted ones included.
