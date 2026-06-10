;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.render-wasm.rulers-state
  "Ruler overlay state derived from the workspace (no WASM/api deps)."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.focus :as cpf]
   [app.common.files.helpers :as cfh]
   [app.common.geom.shapes :as gsh]
   [app.main.data.helpers :as dsh]
   [app.util.color :as uc]
   [app.util.dom :as dom]))

(def ^:private color-css-vars
  {:bg "--panel-background-color"
   :border "--panel-border-color"
   :label "--layer-row-foreground-color"
   :accent "--color-accent-tertiary"})

(defn- display-state*
  [layout selected-shapes base-objects]
  (let [hide-ui? (contains? layout :hide-ui)
        show-rulers? (and (contains? layout :rulers) (not hide-ui?))
        selected-frames (into #{} (map :frame-id) selected-shapes)
        selected-frame (when (= (count selected-frames) 1)
                         (get base-objects (first selected-frames)))
        first-shape (first selected-shapes)
        selecting-first-level-frame? (and (= (count selected-shapes) 1)
                                          (cfh/root-frame? first-shape))]
    {:show-rulers? show-rulers?
     ;; The rounded canvas frame is shown unless the whole UI is hidden.
     :frame-visible? (not hide-ui?)
     :offset-x (if selecting-first-level-frame?
                 (:x first-shape)
                 (:x selected-frame))
     :offset-y (if selecting-first-level-frame?
                 (:y first-shape)
                 (:y selected-frame))
     :ruler-selection (when (and show-rulers? (d/not-empty? selected-shapes))
                        (gsh/shapes->rect selected-shapes))}))

(defn display-state
  [{:keys [layout selected-shapes base-objects]}]
  (display-state* layout selected-shapes base-objects))

(defn from-store
  [state]
  (let [layout (:workspace-layout state)
        file-id (:current-file-id state)
        page-id (:current-page-id state)
        objects (dsh/lookup-page-objects state file-id page-id)
        base-objects (cpf/focus-objects objects (:workspace-focus-selected state))
        selected-shapes (->> (dm/get-in state [:workspace-local :selected])
                             (dsh/process-selected base-objects)
                             (keep (d/getf base-objects))
                             (not-empty))]
    (display-state* layout selected-shapes base-objects)))

(defn theme-colors
  []
  (let [resolve (fn [k]
                  (some-> (dom/get-css-variable (get color-css-vars k) js/document.body)
                          uc/parse-css-color))
        bg (resolve :bg)
        border (resolve :border)
        label (resolve :label)
        accent (resolve :accent)]
    (when (and bg border label accent)
      {:bg bg :border border :label label :accent accent})))
