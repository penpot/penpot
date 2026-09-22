(ns frontend-tests.benches.test-helpers
  "Shared helpers for renderer benchmark fixture tests.

  These helpers assert nothing about shapes or the renderer. They only
  capture errors, read root children in order, and run raw scopes so each
  test namespace tells a complete story without copying bodies."
  (:require
   [app.common.uuid :as uuid]
   [benches.render-wasm.scenes.builder :as builder]))

(defn failure-data
  "Returns the ex-data of the thrown failure, or nil when `f` passes."
  [f]
  (try
    (f)
    nil
    (catch :default cause
      (ex-data cause))))

(defn child-ids
  "Ids listed in the :shapes vector of `id`."
  [instance id]
  (get-in instance [:objects id :shapes]))

(defn children-of
  "Child shapes of `id` in order."
  [instance id]
  (mapv (:objects instance) (child-ids instance id)))

(defn root-shapes
  "Root children of a fixture instance in creation order."
  [instance]
  (children-of instance uuid/zero))

(defn run-scope
  "Runs `f` inside a raw scope and returns the scope state, so tests can
  inspect partial builds without calling finish!."
  [params f]
  (let [state (builder/start params)]
    (binding [builder/*state* state
              builder/*defaults* {}]
      (f))
    state))

(defn selrect
  "Selrect of `shape` as an [x y width height] tuple."
  [shape]
  [(:x (:selrect shape)) (:y (:selrect shape))
   (:width (:selrect shape)) (:height (:selrect shape))])
