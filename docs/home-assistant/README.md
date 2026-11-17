# Home Assistant: voice control through denonmusic-server

```
voice satellite -> Home Assistant (Assist) -> Ollama: utterance -> {action, query}
                                           -> denonmusic-server /intent -> the receiver's HEOS queue
```

Three files:

- [`configuration.yaml`](configuration.yaml) - `rest_command`, `script`, `intent_script` and an
  optional now-playing sensor, to merge into your own `configuration.yaml`.
- [`custom_sentences/en/denon_music.yaml`](custom_sentences/en/denon_music.yaml) - copy to
  `/config/custom_sentences/en/`.
- [`ollama-prompt.txt`](ollama-prompt.txt) - the system prompt on its own, for testing against a
  model. `configuration.yaml` carries an identical copy inline.

Replace `UNRAID_HOST`, `OLLAMA_HOST` and `OLLAMA_MODEL`, put the server's token in `secrets.yaml` as
`denon_api_token`, and restart Home Assistant.

## How a request flows

"Play the wall by pink floyd" matches `DenonMusicRequest`, whose wildcard takes everything after the
verb. The script sends the whole phrase to Ollama, which answers
`{"action":"play","query":"the wall","artist":"pink floyd","type":"any"}`; that goes to `/intent`,
and Assist speaks the `speech` line that comes back ("Playing The Wall by Pink Floyd").

"Pause the stereo", "stereo volume up" and "set the stereo volume to 30" match `DenonControl` and go
straight to `/intent` with no language model in between.

Ollama is called over its own HTTP API rather than through Home Assistant's Ollama conversation
agent, because the API takes `"format": "json"` and a temperature, and the agent does not. If you
would rather use the agent, give it `ollama-prompt.txt` as its instructions and replace the
`denon_parse_utterance` step with `conversation.process`.

## What has and has not been tested

- The YAML parses, and both `payload` templates render to valid JSON (checked with Jinja outside
  Home Assistant).
- **Nothing here has run inside Home Assistant.** Sentence matching, the `response_variable`
  plumbing and the spoken reply are written from Home Assistant's documented behaviour, not observed.
- **The prompt has been run against six models, not through Home Assistant.** The test sent the same
  request body `denon_parse_utterance` sends, for 36 requests built from titles in the real library
  (20 used while writing the prompt, 16 kept back until it was done), and then searched the real
  index with each answer. Temperature 0, one run each, 2026-11-17:

  | Model | Right record and right action (of 36) | Seconds per request |
  |---|---|---|
  | `gemma3:12b` | 35 | 2.2 |
  | `gemma3:4b` | 33 | 0.4 |
  | `hermes3:8b` | 33 | 0.3 |
  | `llama3.2:3b` | 32 | 0.2 |
  | `qwen3:8b`, `qwen3:30b` | 20 of the first 20 | 5 and 37 |

  Every answer was JSON, and all but one (from `llama3.2:3b`, no `type` key, which the script's
  defaults cover) had exactly the four keys. The misses: "next" or "after this" read as plain play,
  which replaces the queue instead (`gemma3:4b` twice, `llama3.2:3b` once); the model changing the
  words ("play some nina simone" kept "some", "something by franz ferdinand" became a search for
  "Something", "folksy" became "folsky"); and "come on december by patty smith" playing the title
  track rather than the album. Start with `gemma3:12b`. The qwen3 models think before answering,
  which is too slow for a spoken request. One run of 36 requests is a small sample: try your own.
- `play {request}` will take over from any built-in "play ..." sentence, for every room. Narrow the
  sentences (for example `play {request} on the stereo`) if that is too greedy.
