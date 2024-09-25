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
   [app.common.types.tokens-lib :as ctob]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.refs :as refs]
   [app.main.ui.workspace.tokens.token :as wtt]
   [app.main.ui.workspace.tokens.token-set :as wtts]
   [app.main.ui.workspace.tokens.update :as wtu]
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
;; TOKENS Getters
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-tokens-lib [state]
  (get-in state [:workspace-data :tokens-lib]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TOKENS Actions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn toggle-or-apply-token
  "Remove any shape attributes from token if they exists.
  Othewise apply token attributes."
  [shape token]
  (let [[shape-leftover token-leftover _matching] (data/diff (:applied-tokens shape) token)]
    (merge {} shape-leftover token-leftover)))

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

(defn get-token-set-data-from-token-set-id
  [id]
  (let [workspace-data (deref refs/workspace-data)]
    (get (:token-sets-index workspace-data) id)))

(defn set-selected-token-set-id
  [id]
  (ptk/reify ::set-selected-token-set-id
    ptk/UpdateEvent
    (update [_ state]
      (wtts/assoc-selected-token-set-id state id))))

(defn get-token-set-tokens
  [token-set file]
  (map #(get-in file [:tokens %]) (:tokens token-set)))

(defn create-token-theme [token-theme]
  (let [new-token-theme token-theme]
    (ptk/reify ::create-token-theme
      ptk/WatchEvent
      (watch [it _ _]
        (let [changes (-> (pcb/empty-changes it)
                          (pcb/add-token-theme new-token-theme))]
          (rx/of
           (dch/commit-changes changes)))))))

(defn update-token-theme [[group name] token-theme]
  (ptk/reify ::update-token-theme
    ptk/WatchEvent
    (watch [it state _]
      (let [tokens-lib (get-tokens-lib state)
            prev-token-theme (some-> tokens-lib (ctob/get-theme group name))
            changes (pcb/update-token-theme (pcb/empty-changes it) token-theme prev-token-theme)]
        (rx/of
         (dch/commit-changes changes))))))

(defn ensure-token-theme-changes [changes state {:keys [id new-set?]}]
  (let [theme-id (wtts/update-theme-id state)
        theme (some-> theme-id (wtts/get-workspace-token-theme state))]
    (cond
     (not theme-id) (-> changes
                        (pcb/add-temporary-token-theme
                         {:id (uuid/next)
                          :name "Test theme"
                          :sets #{id}}))
     new-set? (-> changes
                  (pcb/update-token-theme
                   (wtts/add-token-set-to-token-theme id theme)
                   theme))
     :else changes)))

(defn toggle-token-theme-active? [group name]
  (ptk/reify ::toggle-token-theme-active?
    ptk/WatchEvent
    (watch [it state _]
      (let [tokens-lib (get-tokens-lib state)
            prev-active-token-themes (some-> tokens-lib
                                             (ctob/get-active-theme-paths))
            active-token-themes (some-> tokens-lib
                                        (ctob/toggle-theme-active? group name)
                                        (ctob/get-active-theme-paths))
            changes (pcb/update-active-token-themes (pcb/empty-changes it) active-token-themes prev-active-token-themes)]
        (rx/of
         (dch/commit-changes changes))))))

(defn delete-token-theme [group name]
  (ptk/reify ::delete-token-theme
    ptk/WatchEvent
    (watch [it state _]
      (let [data (get state :workspace-data)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-library-data data)
                        (pcb/delete-token-theme group name))]
        (rx/of
         (dch/commit-changes changes)
         (wtu/update-workspace-tokens))))))

(defn create-token-set [token-set]
  (let [new-token-set (merge
                       {:name "Token Set"
                        :tokens []}
                       token-set)]
    (ptk/reify ::create-token-set
      ptk/WatchEvent
      (watch [it state _]
        (let [changes (-> (pcb/empty-changes it)
                          (pcb/add-token-set new-token-set)
                          #_(ensure-token-theme-changes state {:id (:id new-token-set)
                                                               :new-set? true}))]
          (rx/of
           (set-selected-token-set-id (:name new-token-set))
           (dch/commit-changes changes)))))))

(defn update-token-set [set-name token-set]
  (ptk/reify ::update-token-set
    ptk/WatchEvent
    (watch [it state _]
      (let [prev-token-set (some-> (get-tokens-lib state)
                                   (ctob/get-set set-name))
            changes (-> (pcb/empty-changes it)
                        (pcb/update-token-set token-set prev-token-set))]
        (rx/of
         (dch/commit-changes changes))))))

(defn toggle-token-set [{:keys [token-set-id]}]
  (ptk/reify ::toggle-token-set
    ptk/WatchEvent
    (watch [it state _]
      (let [target-theme-id (wtts/get-temp-theme-id state)
            active-set-ids (wtts/get-active-set-ids state)
            theme (-> (wtts/get-workspace-token-theme target-theme-id state)
                      (assoc :sets active-set-ids))
            changes (-> (pcb/empty-changes it)
                        (pcb/update-token-theme
                         (wtts/toggle-token-set-to-token-theme token-set-id theme)
                         theme)
                        (pcb/update-active-token-themes #{target-theme-id} (wtts/get-active-theme-ids state)))]
        (rx/of
         (dch/commit-changes changes)
         (wtu/update-workspace-tokens))))))

(defn delete-token-set [token-set-name]
  (ptk/reify ::delete-token-set
    ptk/WatchEvent
    (watch [it state _]
      (let [data (get state :workspace-data)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-library-data data)
                        (pcb/delete-token-set token-set-name))]
        (rx/of
         (dch/commit-changes changes)
         (wtu/update-workspace-tokens))))))

(defn update-create-token
  [token]
  (let [token (update token :id #(or % (uuid/next)))]
    (ptk/reify ::update-create-token
      ptk/WatchEvent
      (watch [it state _]
        (let [token-set     (wtts/get-selected-token-set state)
              create-set?   (not token-set)
              token-set     (or token-set
                                {:id (uuid/next)
                                 :name "Global"
                                 :tokens []})

              changes       (cond-> (pcb/empty-changes it)
                              create-set?
                              (pcb/add-token-set token-set))

              prev-token-id (d/seek #(= % (:id token)) (:tokens token-set))
              prev-token    (get-token-data-from-token-id prev-token-id)
              create-token? (not prev-token)

              changes       (if create-token?
                              (pcb/add-token changes (:id token-set) (:name token-set) token)
                              (pcb/update-token changes (:id token-set) (:name token-set) token prev-token))

              changes       (-> changes
                                (ensure-token-theme-changes state {:new-set? create-set?
                                                                   :id (:id token-set)}))]
          (rx/of
           (set-selected-token-set-id (:name token-set))
           (dch/commit-changes changes)))))))

(defn delete-token
  [set-name id name]
  (dm/assert! (string? set-name))
  (dm/assert! (uuid? id))
  (dm/assert! (string? name))
  (ptk/reify ::delete-token
    ptk/WatchEvent
    (watch [it state _]
      (let [data    (get state :workspace-data)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-library-data data)
                        (pcb/delete-token set-name id name))]
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
  [{:keys [position _token-id] :as params}]
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

(defn show-token-set-context-menu
  [{:keys [position _token-set-id] :as params}]
  (dm/assert! (gpt/point? position))
  (ptk/reify ::show-token-set-context-menu
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :token-set-context-menu] params))))

(def hide-token-set-context-menu
  (ptk/reify ::hide-token-set-context-menu
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :token-set-context-menu] nil))))
