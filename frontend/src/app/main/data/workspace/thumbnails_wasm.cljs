;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.workspace.thumbnails-wasm
  "Component thumbnails with render-wasm/v1. The worker renders them
  from the shapes of their mains, which the main thread sends with the
  values of their tokens, and uploads the ones asked for."
  (:require
   [app.common.files.helpers :as cfh]
   [app.common.logic.tokens-propagation :as cltp]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.thumbnails :as dwt]
   [app.main.data.workspace.tokens.correction :as dwtc]
   [app.main.fonts :as fonts]
   [app.main.worker :as mw]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn- token-values
  "Resolved values of the tokens that `shapes` apply, by name, when the last
  tokens check of the workspace is current; nil otherwise or when they
  apply none."
  [state shapes]
  (when-let [resolved (dwtc/current-resolution state)]
    (-> (cltp/resolved-values resolved)
        (select-keys (into #{} (mapcat (comp vals :applied-tokens)) shapes))
        (not-empty))))

(defn render-thumbnail
  "Sends the worker the shapes of the main whose root is `root-id`, to
  render its component thumbnail, and to upload it when `persist?` is
  true. With them go the values of their tokens, since a main of another
  page can be out of date until its page is opened. Nothing comes back:
  `component-thumbnail-events` shows the render."
  [file-id page-id root-id & {:keys [persist?]}]
  (ptk/reify ::render-thumbnail
    ptk/EffectEvent
    (effect [_ state _]
      (let [objects (dsh/lookup-page-objects state file-id page-id)]
        ;; A removed component can bring a nil or deleted root
        (when (contains? objects root-id)
          (let [shapes (cfh/get-children-with-self objects root-id)
                values (token-values state shapes)]
            (mw/emit! (cond-> {:cmd :thumbnails/render-component
                               :file-id file-id
                               :page-id page-id
                               :root-id root-id
                               :persist? persist?
                               :objects (into {} (map (juxt :id identity)) shapes)}
                        (some? values) (assoc :token-values values
                                              :custom-fonts (fonts/custom-fonts))))))))))

(defn component-thumbnail-events
  "Shows each component thumbnail the worker renders for
  `render-thumbnail`."
  []
  (->> (mw/ask-many! {:cmd :thumbnails/watch-components})
       (rx/map (fn [{:keys [object-id blob]}]
                 (dwt/assoc-thumbnail object-id (wapi/create-uri blob))))))
