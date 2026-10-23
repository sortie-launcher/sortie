- Recommend new games
  - This is not something to do immediately, just something to think about how we'd implement moving forward
  - Maybe via Paseo, UI integrated view/shortcut to a remote agent that runs a skill that recommends new games based on:
    - genre clustering
    - existing game metadata
      - eg playtime/times opened/etc
    - other heuristics for player taste
    - recommends games that the user doesn't already have
      - the notable exception here is that this interface should still recommend games that the user has,
        but have never been played or have less than 20 minutes of play time
    - ideally can get structured response from remote agent and then
      render into a browseable saved "Recommendation Snapshot" view/UI
    - Can target recommendations to specific genres,platforms,device performance criteria (eg runs well on Ayn Thor)
- "Horizontal" folder scrolling/view
  - Option to orient folders as 1:1 aspect ratio squares in a grid, allow arrangement in arbitrary verical and horizontal dimensions
- While function is definitely way more important than form, we should add a bit of juice/pizzaz as possible, without taking on noticable perf degradation
- Can we also give controller button hints in the UI
- Can't enable shizuku, as there is no interface for opening installed applications (oversight on my part, forgot to specify this)
- Screen without active UI should be used for something, eg hero cards, some kind of visualization/etc
