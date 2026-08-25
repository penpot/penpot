;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.workspace.reflow.signals
  "Decides which reflow signals a shape update raises: `:layout/update` for the
  shapes whose layout attrs changed, `:text/reflow` for the texts the renderer
  has to re-measure.

  Which text attrs matter depends on the renderer: the DOM one measures every
  changed text, so its own geometry counts as a change; wasm only resizes
  auto-sized texts from their content."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.math :as mth]
   [app.main.features :as features]))

;; If anything a translation can mutate is added here, drop the
;; `(when-not translation? …)` guard in `update-shapes`.
(def ^:private update-layout-attr? #{:hidden})

;; Text attrs that can start async renderer work.
(def ^:private text-reflow-attr?
  #{:content :grow-type :x :y :width :height})

(def ^:private wasm-text-reflow-attr?
  #{:content :grow-type})

(def ^:private dom-text-geometry-reflow-attr?
  #{:x :y :width :height})

(defn- renderer-text-reflow-attr?
  [state]
  (if (features/active-feature? state "render-wasm/v1")
    wasm-text-reflow-attr?
    text-reflow-attr?))

(defn- reflow-attr?
  [state attr]
  (or (update-layout-attr? attr)
      ((renderer-text-reflow-attr? state) attr)))

;; Caller metadata can rule out reflow before objects are compared.
(defn- reflow-candidate?
  [attr? {:keys [attrs translation? update-layout?]
          :or {update-layout? true}}]
  (and update-layout?
       (not translation?)
       (or (nil? attrs)
           (some attr? attrs))))

(defn- text-reflow-changed?
  [state shape changed-shape changed]
  ;; Match the DOM renderer's geometry checks.
  (let [wasm?        (features/active-feature? state "render-wasm/v1")
        reflow-attr? (renderer-text-reflow-attr? state)]
    (some
     (fn [attr]
       (and (reflow-attr? attr)
            (or wasm?
                (not (dom-text-geometry-reflow-attr? attr))
                (not (mth/close? (get shape attr)
                                 (get changed-shape attr))))))
     changed)))

(defn- async-text-reflow?
  "Whether `shape` enters an asynchronous text geometry pipeline. The HTML
  renderer measures every changed text; WASM only resizes auto-sized texts.
  A grow-type transition is included because `shape` is the value before the
  update and may still be fixed."
  [state shape changed]
  (and (cfh/text-shape? shape)
       (or (not (features/active-feature? state "render-wasm/v1"))
           (not= :fixed (:grow-type shape))
           (contains? changed :grow-type))))

(defn- get-reflow-changes
  [state objects changed-objects ids {:keys [attrs] :as props}]
  ;; Reuse built objects so update functions only run once.
  (let [reflow-attr? (partial reflow-attr? state)]
    (when (reflow-candidate? reflow-attr? props)
      (into []
            (comp
             (map (d/getf objects))
             (keep (fn [shape]
                     (let [changed-shape (get changed-objects (:id shape))
                           changed       (pcb/changed-attrs
                                          shape objects (constantly changed-shape)
                                          {:attrs attrs})]
                       (when (some reflow-attr? changed)
                         [shape changed-shape changed])))))
            ids))))

(defn- get-layout-reflow-ids
  [reflow-changes]
  (->> reflow-changes
       (into [] (comp (filter (fn [[_ _ changed]] (some update-layout-attr? changed)))
                      (map (comp :id first))))
       (not-empty)))

(defn- get-text-reflow-ids
  [state page-id reflow-changes]
  ;; Track measurable texts on the active page.
  (when (= page-id (get state :current-page-id))
    (let [edition (dm/get-in state [:workspace-local :edition])]
      (->> reflow-changes
           (into [] (comp (filter (fn [[shape changed-shape changed]]
                                    (and (async-text-reflow? state shape changed)
                                         (text-reflow-changed?
                                          state shape changed-shape changed))))
                          (map (comp :id first))
                          (remove #(= % edition))))
           (not-empty)))))

(defn reflow-ids
  "Ids a shape update has to signal: `:layout-ids` for `:layout/update`,
  `:text-ids` for `:text/reflow`. Both are nil when nothing changed.

  Both sets come from one comparison pass, so `update-fn` and the attribute
  diff only run once per shape."
  [state page-id objects changed-objects ids props]
  (let [reflow-changes (get-reflow-changes state objects changed-objects ids props)]
    {:layout-ids (get-layout-reflow-ids reflow-changes)
     :text-ids   (get-text-reflow-ids state page-id reflow-changes)}))

(defn text-reflow-candidate?
  "Whether `props` can start renderer text work, judged from the caller metadata
  alone. Cheap pre-filter for callers that buffer updates before they have
  objects to compare."
  [state props]
  (reflow-candidate? (renderer-text-reflow-attr? state) props))

(defn new-text-reflow?
  "Whether a newly added `shape` enters an asynchronous text geometry pipeline."
  [state shape]
  (async-text-reflow? state shape nil))
