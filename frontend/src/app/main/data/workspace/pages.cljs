;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.pages
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.types.component :as ctc]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.page :as ctp]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.changes :as dch]
   [app.main.data.common :as dcm]
   [app.main.data.event :as ev]
   [app.main.data.helpers :as dsh]
   [app.main.data.persistence :as-alias dps]
   [app.main.data.workspace.drawing :as dwd]
   [app.main.data.workspace.layout :as layout]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.thumbnails :as dwth]
   [app.main.errors]
   [app.main.router :as rt]
   [app.util.http :as http]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

(def default-workspace-local {:zoom 1})

(defn- select-frame-tool
  [file-id page-id]
  (ptk/reify ::select-frame-tool
    ptk/WatchEvent
    (watch [_ state _]
      (let [page (dsh/lookup-page state file-id page-id)]
        (when (ctp/is-empty? page)
          (rx/of (dwd/select-for-drawing :frame)))))))

(def ^:private xf:collect-file-media
  "Resolve and collect all file media on page objects"
  (comp (map second)
        (keep (fn [{:keys [metadata fill-image]}]
                (cond
                  (some? metadata)   (cf/resolve-file-media metadata)
                  (some? fill-image) (cf/resolve-file-media fill-image))))))


(defn- initialize-page*
  "Second phase of page initialization, once we know the page is
  available in the state"
  [file-id page-id page]
  (ptk/reify ::initialize-page*
    ptk/UpdateEvent
    (update [_ state]
      ;; selection; when user abandon the current page, the selection is lost
      (let [local (dm/get-in state [:workspace-cache [file-id page-id]] default-workspace-local)]
        (-> state
            (assoc :current-page-id page-id)
            (assoc :workspace-local (assoc local :selected (d/ordered-set)))
            (assoc :workspace-trimmed-page (dm/select-keys page [:id :name]))

            ;; FIXME: this should be done on `initialize-layout` (?)
            (update :workspace-layout layout/load-layout-flags)
            (update :workspace-global layout/load-layout-state))))

    ptk/EffectEvent
    (effect [_ _ _]
      (let [uris  (into #{} xf:collect-file-media (:objects page))]
        (->> (rx/from uris)
             (rx/subs! #(http/fetch-data-uri % false)))))))

(defn initialize-page
  [file-id page-id]
  (assert (uuid? file-id) "expected valid uuid for `file-id`")
  (assert (uuid? page-id) "expected valid uuid for `page-id`")

  (ptk/reify ::initialize-page
    ptk/WatchEvent
    (watch [_ state _]
      (if-let [page (dsh/lookup-page state file-id page-id)]
        (rx/concat
         (rx/of (initialize-page* file-id page-id page)
                (dwth/watch-state-changes file-id page-id)
                (dwl/watch-component-changes))
         (let [profile (:profile state)
               props   (get profile :props)]
           (when (not (:workspace-visited props))
             (rx/of (select-frame-tool file-id page-id)))))

        ;; NOTE: this redirect is necessary for cases where user
        ;; explicitly passes an non-existing page-id on the url
        ;; params, so on check it we can detect that there are no data
        ;; for the page and redirect user to an existing page
        (rx/of (dcm/go-to-workspace :file-id file-id ::rt/replace true))))))

(defn finalize-page
  [file-id page-id]
  (assert (uuid? file-id) "expected valid uuid for `file-id`")
  (assert (uuid? page-id) "expected valid uuid for `page-id`")

  (ptk/reify ::finalize-page
    ptk/UpdateEvent
    (update [_ state]
      (let [local (-> (:workspace-local state)
                      (dissoc :edition :edit-path :selected))
            exit? (not= :workspace (rt/lookup-name state))
            state (-> state
                      (update :workspace-cache assoc [file-id page-id] local)
                      (dissoc :current-page-id
                              :workspace-local
                              :workspace-trimmed-page
                              :workspace-focus-selected))]

        (cond-> state
          exit? (dissoc :workspace-drawing))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workspace Page CRUD
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-page
  [{:keys [page-id file-id]}]
  (let [id (or page-id (uuid/next))]
    (ptk/reify ::create-page
      ev/Event
      (-data [_]
        {:id id
         :file-id file-id})

      ptk/WatchEvent
      (watch [it state _]
        (let [pages   (-> (dsh/lookup-file-data state)
                          (get :pages-index))
              unames  (cfh/get-used-names pages)
              name    (cfh/generate-unique-name "Page" unames :immediate-suffix? true)
              changes (-> (pcb/empty-changes it)
                          (pcb/add-empty-page id name))]

          (rx/of (dch/commit-changes changes)))))))

(defn duplicate-page
  [page-id]
  (ptk/reify ::duplicate-page
    ptk/WatchEvent
    (watch [it state _]
      (let [id                 (uuid/next)
            fdata              (dsh/lookup-file-data state)
            pages              (get fdata :pages-index)
            page               (get pages page-id)

            unames             (cfh/get-used-names pages)
            suffix-fn          (fn [copy-count]
                                 (str/concat " "
                                             (tr "dashboard.copy-suffix")
                                             (when (> copy-count 1)
                                               (str " " copy-count))))
            base-name          (:name page)
            name               (cfh/generate-unique-name base-name unames :suffix-fn suffix-fn)
            objects            (update-vals (:objects page) #(dissoc % :use-for-thumbnail))

            main-instances-ids (set (keep #(when (ctc/main-instance? (val %)) (key %)) objects))
            ids-to-remove      (set (apply concat (map #(cfh/get-children-ids objects %) main-instances-ids)))

            add-component-copy
            (fn [objs id shape]
              (let [component (ctkl/get-component fdata (:component-id shape))
                    parent-id (when (not= (:parent-id shape) uuid/zero) (:parent-id shape))
                    [new-shape new-shapes]
                    (ctn/make-component-instance page
                                                 component
                                                 fdata
                                                 (gpt/point (:x shape) (:y shape))
                                                 {:keep-ids? true
                                                  :force-frame-id (:frame-id shape)
                                                  :force-parent-id parent-id})
                    children (into {} (map (fn [shape] [(:id shape) shape]) new-shapes))
                    objs (assoc objs id new-shape)]
                (merge objs children)))

            objects
            (reduce
             (fn [objs [id shape]]
               (cond (contains? main-instances-ids id)
                     (add-component-copy objs id shape)
                     (contains? ids-to-remove id)
                     objs
                     :else
                     (assoc objs id shape)))
             {}
             objects)

            page    (-> page
                        (assoc :name name)
                        (assoc :id id)
                        (assoc :objects
                               objects))
            changes (-> (pcb/empty-changes it)
                        (pcb/add-page id page))]
        (rx/of (dch/commit-changes changes))))))

(s/def ::rename-page
  (s/keys :req-un [::id ::name]))

(defn rename-page
  [id name]
  (dm/assert! (uuid? id))
  (dm/assert! (string? name))
  (ptk/reify ::rename-page
    ptk/WatchEvent
    (watch [it state _]
      (let [page    (dsh/lookup-page state id)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-page page)
                        (pcb/mod-page page {:name name}))]
        (rx/of (dch/commit-changes changes))))))

(defn- delete-page-components
  [changes page]
  (let [components-to-delete (->> page
                                  :objects
                                  vals
                                  (filter #(true? (:main-instance %)))
                                  (map :component-id))

        changes (reduce (fn [changes component-id]
                          (pcb/delete-component changes component-id (:id page)))
                        changes
                        components-to-delete)]
    changes))

(defn delete-page
  [id]
  (ptk/reify ::delete-page
    ptk/WatchEvent
    (watch [it state _]
      (let [file-id (:current-file-id state)
            fdata   (dsh/lookup-file-data state file-id)
            pindex  (:pages-index fdata)
            pages   (:pages fdata)

            index   (d/index-of pages id)
            page    (get pindex id)
            page    (assoc page :index index)
            pages   (filter #(not= % id) pages)

            changes (-> (pcb/empty-changes it)
                        (pcb/with-library-data fdata)
                        (delete-page-components page)
                        (pcb/del-page page))]

        (rx/of (dch/commit-changes changes)
               (when (= id (:current-page-id state))
                 (dcm/go-to-workspace {:page-id (first pages)})))))))
