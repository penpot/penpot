;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.tokens
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.geom.point :as gpt]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.refs :as refs]
   [app.main.ui.workspace.tokens.common :refer [workspace-shapes]]
   [app.main.ui.workspace.tokens.token :as wtt]
   [beicon.v2.core :as rx]
   [clojure.data :as data]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; TODO HYMA: Copied over from workspace.cljs
(defn update-shape
  [id attrs]
  (dm/assert!
   "expected valid parameters"
   (and (cts/check-shape-attrs! attrs)
        (uuid? id)))

  (ptk/reify ::update-shape
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (dwsh/update-shapes [id] #(merge % attrs))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TOKENS Actions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn toggle-or-apply-token
  "Remove any shape attributes from token if they exists.
  Othewise apply token attributes."
  [shape token]
  (let [[shape-leftover token-leftover _matching] (data/diff (:applied-tokens shape) token)]
    (merge {} shape-leftover token-leftover)))

(defn get-shape-from-state [shape-id state]
  (let [current-page-id (get state :current-page-id)
        shape (-> (workspace-shapes (:workspace-data state) current-page-id #{shape-id})
                  (first))]
    shape))

(defn token-from-attributes [token attributes]
  (->> (map (fn [attr] [attr (wtt/token-identifier token)]) attributes)
       (into {})))

(defn unapply-token-id [shape attributes]
  (update shape :applied-tokens d/without-keys attributes))

(defn apply-token-to-attributes [{:keys [shape token attributes]}]
  (let [token (token-from-attributes token attributes)]
    (toggle-or-apply-token shape token)))

(defn apply-token-to-shape
  [{:keys [shape token attributes] :as _props}]
  (let [applied-tokens (apply-token-to-attributes {:shape shape
                                                   :token token
                                                   :attributes attributes})]
    (update shape :applied-tokens #(merge % applied-tokens))))

(defn maybe-apply-token-to-shape
  "When the passed `:token` is non-nil apply it to the `:applied-tokens` on a shape."
  [{:keys [shape token _attributes] :as props}]
  (if token
    (apply-token-to-shape props)
    shape))

(defn get-token-data-from-token-id
  [id]
  (let [workspace-data (deref refs/workspace-data)]
    (get (:tokens workspace-data) id)))

(defn update-create-token
  [token]
  (let [token (update token :id #(or % (uuid/next)))]
    (ptk/reify ::add-token
      ptk/WatchEvent
      (watch [it _ _]
        (let [prev-token (get-token-data-from-token-id (:id token))
              changes (if prev-token
                        (-> (pcb/empty-changes it)
                            (pcb/update-token token prev-token))
                        (-> (pcb/empty-changes it)
                            (pcb/add-token token)))]
          (rx/of (dch/commit-changes changes)))))))

(defn delete-token
  [id]
  (dm/assert! (uuid? id))
  (ptk/reify ::delete-token
    ptk/WatchEvent
    (watch [it state _]
      (let [data    (get state :workspace-data)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-library-data data)
                        (pcb/delete-token id))]
        (rx/of (dch/commit-changes changes))))))

(defn duplicate-token
  [id]
  (let [new-token (-> (get-token-data-from-token-id id)
                      (dissoc :id)
                      (update :name #(str/concat % "-copy")))]
    (update-create-token new-token)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TEMP (Move to test)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(comment
  (def shape-1 {:r3 3})

  (def token-1 {:rx 1
                :ry 1})


  (def shape-after-token-1-is-applied {:rx 1
                                       :ry 1
                                       :r3 3})

  (def token-2 {:r3 1})


  (def shape-after-token-2-is-applied {:rx 1
                                       :ry 1
                                       :r3 1})

  (def token-3 {:r3 1})

  (def shape-after-token-3-is-applied {:rx 1
                                       :ry 1})

  (= (toggle-or-apply-token shape-1 token-1)
     shape-after-token-1-is-applied)
  (= (toggle-or-apply-token shape-after-token-1-is-applied token-2)
     shape-after-token-2-is-applied)
  (= (toggle-or-apply-token shape-after-token-2-is-applied token-3)
     shape-after-token-3-is-applied)
  nil)

(defn set-token-type-section-open
  [token-type open?]
  (ptk/reify ::set-token-type-section-open
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-tokens :open-status token-type] open?))))

;; Token Context Menu Functions -------------------------------------------------

(defn show-token-context-menu
  [{:keys [position token-id] :as params}]
  (dm/assert! (gpt/point? position))
  (ptk/reify ::show-token-context-menu
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :token-context-menu] params))))

(def hide-token-context-menu
  (ptk/reify ::hide-token-context-menu
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :token-context-menu] nil))))
