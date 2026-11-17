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
- **The prompt has not been run against a model** - the Ollama instance was unreachable when this was
  written. Try it first: `ollama run <model>` with the prompt as the system message and a few
  requests of your own. A small model that adds commentary or invents artists needs a bigger one.
- `play {request}` will take over from any built-in "play ..." sentence, for every room. Narrow the
  sentences (for example `play {request} on the stereo`) if that is too greedy.
