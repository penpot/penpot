;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.workspace.tokens.page-values
  "Sets the token values of a page, without the state of the workspace: the
  main thread uses it for the pages it propagates and the drain worker for
  the pages left out of date and their thumbnails (`app.worker.tokens-drain`)."
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.logic.tokens-propagation :as cltp]
   [app.common.types.component :as ctk]
   [app.main.data.workspace.tokens.lookup :as lookup]))

(def token-change-types
  "Types of the changes that may change the value of a token."
  #{:set-tokens-lib :set-token :set-token-set :set-token-theme
    :rename-token-set-group :move-token-set :move-token-set-group
    :set-tokens-status :set-own-tokens-status :set-tokens-source
    :set-base-font-size})

(defn tokens-change?
  "True when some of `changes` may change the value of a token."
  [changes]
  (boolean (some #(contains? token-change-types (:type %)) changes)))

(defn candidate-ids
  "Ids of the shapes of `objects` that may be out of date: the ones that
  apply some of the tokens `token-names`, or every shape with tokens when
  `token-names` is nil."
  [objects token-names]
  (if (some? token-names)
    (lookup/shapes-using objects token-names)
    (into #{} cat (vals (lookup/token-shape-ids objects)))))

(defn page-affected?
  "True when `page` may have something to set for the tokens `token-names`,
  or for any token when it is nil."
  [page token-names]
  (or (nil? token-names)
      (some? (:background-token page))
      (seq (candidate-ids (:objects page) token-names))))

(defn root-frame-ids
  "Ids of the root frames that hold the shapes `ids` of `objects`."
  [objects ids]
  (into #{} (keep #(cfh/get-shape-id-root-frame objects %)) ids))

(defn main-instance-ids
  "Ids of the main components that hold the shapes `ids` of `objects`, at
  any depth: their thumbnails are kept by that id."
  [objects ids]
  (into #{}
        (comp (mapcat #(cfh/get-parent-ids-seq-with-self objects %))
              (distinct)
              (filter #(ctk/main-instance? (get objects %))))
        ids))

(defn stale-thumbnail-ids
  "`{:frame-ids :main-ids}`: the root frames and the other main components
  whose thumbnails go out of date when the shapes `ids` of `objects` change."
  [objects ids]
  (let [frame-ids (root-frame-ids objects ids)]
    {:frame-ids frame-ids
     :main-ids  (into #{} (remove frame-ids) (main-instance-ids objects ids))}))

(defn page-propagation
  "`changes` with the token `targets` of `page` set to `resolved-tokens`, and
  its background to the one of its background token. `opts` go to
  `cltp/generate-token-propagation`."
  [changes page resolved-tokens targets opts]
  (let [background (let [color (cltp/page-background page resolved-tokens)]
                     (when (and (some? color) (not= color (:background page)))
                       color))]
    (-> changes
        (pcb/with-page page)
        (cltp/generate-token-propagation (:objects page) resolved-tokens targets opts)
        (cond-> (some? background) (pcb/mod-page page {:background background})))))

(defn changed-shape-ids
  "Ids of the shapes that `changes` modify."
  [changes]
  (into []
        (comp (filter #(= :mod-obj (:type %)))
              (map :id)
              (distinct))
        (:redo-changes changes)))

(defn page-stale-thumbnails
  "`{:frame-ids :main-ids}` of `page` whose thumbnails show shapes that apply
  the tokens `token-names` and are out of date with `values` (token name to
  resolved value). Nil when there are none."
  [page values token-names]
  (let [objects  (:objects page)
        resolved (cltp/values->resolved-tokens values)
        ids      (keys (cltp/out-of-date-targets objects resolved
                                                 :ids (candidate-ids objects token-names)
                                                 :unassigned? true))]
    (when (seq ids)
      (stale-thumbnail-ids objects ids))))

(defn drain-page
  "The changes that set the shapes of `page`, a page that is not the current
  one, that apply the tokens `token-names` and are out of date with
  `values` (token name to resolved value), as a derived change with no undo
  entry; with the root frames and main components whose thumbnails they
  leave out of date. Nil when there is nothing to set."
  [page values token-names fonts]
  (let [objects  (:objects page)
        resolved (cltp/values->resolved-tokens values)
        targets  (cltp/out-of-date-targets objects resolved
                                           :ids (candidate-ids objects token-names)
                                           :unassigned? true)
        changes  (-> (pcb/empty-changes nil (:id page))
                     (page-propagation page resolved targets
                                       {:fonts fonts
                                        :geometry :cljs
                                        :reset-position-data? true})
                     (pcb/set-save-undo? false))]
    (when (seq (:redo-changes changes))
      (merge {:redo-changes (:redo-changes changes)
              :undo-changes (:undo-changes changes)}
             (stale-thumbnail-ids objects (changed-shape-ids changes))))))
