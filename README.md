# gmx-gotify-ingest

Ingest [git-memex](https://github.com/walterl/git-memex) entries from a [Gotify](https://gotify.net/) channel.

This allows one to (e.g.) add git-memex entries using the [Gotify mobile app](https://f-droid.org/de/packages/com.github.gotify/).

## Setup

1. [Install Babashka](https://book.babashka.org/#_installation) somewhere in your `$PATH`.
2. Configuration: Copy `env.sh` to `~/.config/gmx-gotify-ingest/env.sh` and update values.
  - `GGI_GOTIFY_APP_ID` is the number in the URL after selecting the app in the left-hand column.
  - `GGI_GOTIFY_APP_TOKEN` is the *client* token you get when clicking *Clients* in the Gotify toolbar.
3. Add crontab entry:
   ```crontab
   10 * * * * bash -l /path/to/gmx-gotify-ingest/gmx_gotify_ingest.sh
   ```

## License

[MIT](./LICENSE.md)
