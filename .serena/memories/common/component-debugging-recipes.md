# Common Component and Change Debugging Recipes

Keep source changes out of these recipes unless the task requires a durable fix.

## Inspect recent workspace changes

From `cljs_repl` after triggering an action:

```clojure
(let [items (get-in @app.main.store/state [:workspace-undo :items])
      n (count items)]
  (->> items
       (drop (max 0 (- n 5)))
       (map-indexed (fn [i it]
                      {:idx (+ i (max 0 (- n 5)))
                       :tags (:tags it)
                       :n (count (:redo-changes it))
                       :types (frequencies (map :type (:redo-changes it)))
                       :ids (mapv :id (:redo-changes it))}))))
```

To inspect operations within the latest `:mod-obj`:

```clojure
(let [items (get-in @app.main.store/state [:workspace-undo :items])
      mod-obj (->> (:redo-changes (last items))
                   (filter #(= :mod-obj (:type %)))
                   first)]
  (:operations mod-obj))
```

## Trace variant switch attribute copying

To capture what `update-attrs-on-switch` saw during a real UI swap, patch it temporarily in `cljs_repl`:

```clojure
(def orig (deref #'app.common.logic.libraries/update-attrs-on-switch))
(def trace-buf (atom []))
(set! app.common.logic.libraries/update-attrs-on-switch
      (fn [& args]
        (swap! trace-buf conj
               (let [[_ curr prev _ _ origin _] args]
                 {:curr (select-keys curr [:name :x :y :selrect :points :touched])
                  :prev (select-keys prev [:name :x :y :selrect :points :touched])
                  :origin-ref (select-keys origin [:id :name :x :y :width :height :selrect])}))
        (apply orig args)))
;; trigger UI action, then inspect @trace-buf
(set! app.common.logic.libraries/update-attrs-on-switch orig)
```

Runtime patching is faster than adding temporary source instrumentation and avoids recompilation cleanup. Restore the var or reload the frontend when finished.

## Test-side helpers

- Use `thf/dump-file file :keys [...]` to print a shape tree with selected keys during common tests.
- Prefer production-path helpers such as `cls/generate-update-shapes` plus `thf/apply-changes` for shape mutations.
- For component swaps with keep-touched behavior, use `tho/swap-component-in-shape` with `{:keep-touched? true}`.
- Temporary `prn` calls in production code are acceptable while investigating but should be removed before committing.