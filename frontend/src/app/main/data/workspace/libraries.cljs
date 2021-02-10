;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.data.workspace.libraries
  (:require
   [app.common.data :as d]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.common.pages :as cp]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as geom]
   [app.main.data.messages :as dm]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.libraries-helpers :as dwlh]
   [app.common.pages :as cp]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.util.color :as color]
   [app.util.i18n :refer [tr]]
   [app.util.router :as rt]
   [app.util.time :as dt]
   [app.util.logging :as log]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]))

;; Change this to :info :debug or :trace to debug this module
(log/set-level! :warn)

(defn- log-changes
  [changes file]
  (let [extract-change
        (fn [change]
          (let [shape (when (:id change)
                        (cond
                          (:page-id change)
                          (get-in file [:pages-index
                                        (:page-id change)
                                        :objects
                                        (:id change)])
                          (:component-id change)
                          (get-in file [:components
                                        (:component-id change)
                                        :objects
                                        (:id change)])
                          :default nil))

                prefix (if (:component-id change) "[C] " "[P] ")

                extract (cond-> {:type (:type change)
                                 :change change}
                          shape
                          (assoc :shape (str prefix (:name shape)))
                          (:operations change)
                          (assoc :operations (:operations change)))]
            extract))]
    (map extract-change changes)))

(declare sync-file)

(defn set-assets-box-open
  [file-id box open?]
  (ptk/reify ::set-assets-box-open
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :assets-files-open file-id box] open?))))

(defn default-color-name [color]
  (or (:color color)
      (case (get-in color [:gradient :type])
        :linear (tr "workspace.gradients.linear")
        :radial (tr "workspace.gradients.radial"))))

(defn add-color
  [color]
  (let [id   (uuid/next)
        color (assoc color
                     :id id
                     :name (default-color-name color))]
    (us/assert ::cp/color color)
    (ptk/reify ::add-color
      ptk/WatchEvent
      (watch [_ state s]
        (let [rchg {:type :add-color
                    :color color}
              uchg {:type :del-color
                    :id id}]
          (rx/of #(assoc-in % [:workspace-local :color-for-rename] id)
                 (dwc/commit-changes [rchg] [uchg] {:commit-local? true})))))))

(defn add-recent-color
  [color]
  (us/assert ::cp/recent-color color)
  (ptk/reify ::add-recent-color
    ptk/WatchEvent
    (watch [_ state s]
      (let [rchg {:type :add-recent-color
                  :color color}]
        (rx/of (dwc/commit-changes [rchg] [] {:commit-local? true}))))))

(def clear-color-for-rename
  (ptk/reify ::clear-color-for-rename
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :color-for-rename] nil))))

(defn update-color
  [{:keys [id] :as color} file-id]
  (us/assert ::cp/color color)
  (us/assert ::us/uuid file-id)
  (ptk/reify ::update-color
    ptk/WatchEvent
    (watch [_ state stream]
      (let [prev (get-in state [:workspace-data :colors id])
            rchg {:type :mod-color
                  :color color}
            uchg {:type :mod-color
                  :color prev}]
        (rx/of (dwc/commit-changes [rchg] [uchg] {:commit-local? true})
               (sync-file (:current-file-id state) file-id))))))

(defn delete-color
  [{:keys [id] :as params}]
  (us/assert ::us/uuid id)
  (ptk/reify ::delete-color
    ptk/WatchEvent
    (watch [_ state stream]
      (let [prev (get-in state [:workspace-data :colors id])
            rchg {:type :del-color
                  :id id}
            uchg {:type :add-color
                  :color prev}]
        (rx/of (dwc/commit-changes [rchg] [uchg] {:commit-local? true}))))))

(defn add-media
  [{:keys [id] :as media}]
  (us/assert ::cp/media-object media)
  (ptk/reify ::add-media
    ptk/WatchEvent
    (watch [_ state stream]
      (let [obj  (select-keys media [:id :name :width :height :mtype])
            rchg {:type :add-media
                  :object obj}
            uchg {:type :del-media
                  :id id}]
        (rx/of (dwc/commit-changes [rchg] [uchg] {:commit-local? true}))))))

(defn rename-media
  [id new-name]
  (us/assert ::us/uuid id)
  (us/assert ::us/string new-name)
  (ptk/reify ::rename-media
    ptk/WatchEvent
    (watch [_ state stream]
      (let [object (get-in state [:workspace-data :media id])

            rchanges [{:type :mod-media
                       :object {:id id
                                :name new-name}}]

            uchanges [{:type :mod-media
                       :object {:id id
                                :name (:name object)}}]]

        (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true}))))))

(defn delete-media
  [{:keys [id] :as params}]
  (us/assert ::us/uuid id)
  (ptk/reify ::delete-media
    ptk/WatchEvent
    (watch [_ state stream]
      (let [prev (get-in state [:workspace-data :media id])
            rchg {:type :del-media
                  :id id}
            uchg {:type :add-media
                  :object prev}]
        (rx/of (dwc/commit-changes [rchg] [uchg] {:commit-local? true}))))))

(defn add-typography
  ([typography] (add-typography typography true))
  ([typography edit?]
   (let [typography (update typography :id #(or % (uuid/next)))]
     (us/assert ::cp/typography typography)
     (ptk/reify ::add-typography
       ptk/WatchEvent
       (watch [_ state s]
         (let [rchg {:type :add-typography
                     :typography (assoc typography :ts (.now js/Date))}
               uchg {:type :del-typography
                     :id (:id typography)}]
           (rx/of (dwc/commit-changes [rchg] [uchg] {:commit-local? true})
                  #(cond-> %
                     edit?
                     (assoc-in [:workspace-local :rename-typography] (:id typography))))))))))

(defn update-typography
  [typography file-id]
  (us/assert ::cp/typography typography)
  (us/assert ::us/uuid file-id)
  (ptk/reify ::update-typography
    ptk/WatchEvent
    (watch [_ state stream]
      (let [prev (get-in state [:workspace-data :typographies (:id typography)])
            rchg {:type :mod-typography
                  :typography typography}
            uchg {:type :mod-typography
                  :typography prev}]
        (rx/of (dwc/commit-changes [rchg] [uchg] {:commit-local? true})
               (sync-file (:current-file-id state) file-id))))))

(defn delete-typography
  [id]
  (us/assert ::us/uuid id)
  (ptk/reify ::delete-typography
    ptk/WatchEvent
    (watch [_ state stream]
      (let [prev (get-in state [:workspace-data :typographies id])
            rchg {:type :del-typography
                  :id id}
            uchg {:type :add-typography
                  :typography prev}]
        (rx/of (dwc/commit-changes [rchg] [uchg] {:commit-local? true}))))))

(def add-component
  "Add a new component to current file library, from the currently selected shapes."
  (ptk/reify ::add-component
    ptk/WatchEvent
    (watch [_ state stream]
      (let [file-id  (:current-file-id state)
            page-id  (:current-page-id state)
            objects  (dwc/lookup-page-objects state page-id)
            selected (get-in state [:workspace-local :selected])
            selected (cp/clean-loops objects selected)]
        (let [[group rchanges uchanges]
              (dwlh/generate-add-component selected objects page-id file-id)]
          (when-not (empty? rchanges)
            (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true})
                   (dwc/select-shapes (d/ordered-set (:id group))))))))))

(defn rename-component
  "Rename the component with the given id, in the current file library."
  [id new-name]
  (us/assert ::us/uuid id)
  (us/assert ::us/string new-name)
  (ptk/reify ::rename-component
    ptk/WatchEvent
    (watch [_ state stream]
      (let [component (get-in state [:workspace-data :components id])
            objects (get component :objects)
            new-objects (assoc-in objects
                                  [(:id component) :name]
                                  new-name)

            rchanges [{:type :mod-component
                       :id id
                       :name new-name
                       :objects new-objects}]

            uchanges [{:type :mod-component
                       :id id
                       :name (:name component)
                       :objects objects}]]

        (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true}))))))

(defn duplicate-component
  "Create a new component copied from the one with the given id."
  [{:keys [id] :as params}]
  (ptk/reify ::duplicate-component
    ptk/WatchEvent
    (watch [_ state stream]
      (let [component      (cp/get-component id
                                             (:current-file-id state)
                                             (dwlh/get-local-file state)
                                              nil)
            all-components (vals (get-in state [:workspace-data :components]))
            unames         (set (map :name all-components))
            new-name       (dwc/generate-unique-name unames (:name component))

            [new-shape new-shapes updated-shapes]
            (dwlh/duplicate-component component)

            rchanges [{:type :add-component
                       :id (:id new-shape)
                       :name new-name
                       :shapes new-shapes}]

            uchanges [{:type :del-component
                       :id (:id new-shape)}]]

        (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true}))))))

(defn delete-component
  "Delete the component with the given id, from the current file library."
  [{:keys [id] :as params}]
  (us/assert ::us/uuid id)
  (ptk/reify ::delete-component
    ptk/WatchEvent
    (watch [_ state stream]
      (let [component (get-in state [:workspace-data :components id])

            rchanges [{:type :del-component
                       :id id}]

            uchanges [{:type :add-component
                       :id id
                       :name (:name component)
                       :shapes (vals (:objects component))}]]

        (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true}))))))

(defn instantiate-component
  "Create a new shape in the current page, from the component with the given id
  in the given file library. Then selects the newly created instance."
  [file-id component-id position]
  (us/assert ::us/uuid file-id)
  (us/assert ::us/uuid component-id)
  (us/assert ::us/point position)
  (ptk/reify ::instantiate-component
    ptk/WatchEvent
    (watch [_ state stream]
      (let [local-library   (dwlh/get-local-file state)
            libraries       (get state :workspace-libraries)
            component       (cp/get-component component-id file-id local-library libraries)
            component-shape (cp/get-shape component component-id)

            orig-pos  (gpt/point (:x component-shape) (:y component-shape))
            delta     (gpt/subtract position orig-pos)

            page-id   (:current-page-id state)
            objects   (dwc/lookup-page-objects state page-id)
            unames    (atom (dwc/retrieve-used-names objects))

            frame-id (cp/frame-id-by-position objects (gpt/add orig-pos delta))

            update-new-shape
            (fn [new-shape original-shape]
              (let [new-name (dwc/generate-unique-name @unames (:name new-shape))]

                (when (nil? (:parent-id original-shape))
                  (swap! unames conj new-name))

                (cond-> new-shape
                  true
                  (as-> $
                    (geom/move $ delta)
                    (assoc $ :frame-id frame-id)
                    (assoc $ :parent-id
                           (or (:parent-id $) (:frame-id $)))
                    (dissoc $ :touched))

                  (nil? (:shape-ref original-shape))
                  (assoc :shape-ref (:id original-shape))

                  (nil? (:parent-id original-shape))
                  (assoc :component-id (:id original-shape)
                         :component-file file-id
                         :component-root? true
                         :name new-name)

                  (some? (:parent-id original-shape))
                  (dissoc :component-root?))))

            [new-shape new-shapes _]
            (cp/clone-object component-shape
                              nil
                              (get component :objects)
                              update-new-shape)

            rchanges (map (fn [obj]
                            {:type :add-obj
                             :id (:id obj)
                             :page-id page-id
                             :frame-id (:frame-id obj)
                             :parent-id (:parent-id obj)
                             :ignore-touched true
                             :obj obj})
                          new-shapes)

            uchanges (map (fn [obj]
                            {:type :del-obj
                             :id (:id obj)
                             :page-id page-id
                             :ignore-touched true})
                          new-shapes)]

        (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true})
               (dwc/select-shapes (d/ordered-set (:id new-shape))))))))

(defn detach-component
  "Remove all references to components in the shape with the given id,
  and all its children, at the current page."
  [id]
  (us/assert ::us/uuid id)
  (ptk/reify ::detach-component
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (:current-page-id state)
            objects (dwc/lookup-page-objects state page-id)
            shapes (cp/get-object-with-children id objects)

            rchanges (map (fn [obj]
                            {:type :mod-obj
                             :page-id page-id
                             :id (:id obj)
                             :operations [{:type :set
                                           :attr :component-id
                                           :val nil}
                                          {:type :set
                                           :attr :component-file
                                           :val nil}
                                          {:type :set
                                           :attr :component-root?
                                           :val nil}
                                          {:type :set
                                           :attr :remote-synced?
                                           :val nil}
                                          {:type :set
                                           :attr :shape-ref
                                           :val nil}
                                          {:type :set
                                           :attr :touched
                                           :val nil}]})
                          shapes)

            uchanges (map (fn [obj]
                            {:type :mod-obj
                             :page-id page-id
                             :id (:id obj)
                             :operations [{:type :set
                                           :attr :component-id
                                           :val (:component-id obj)}
                                          {:type :set
                                           :attr :component-file
                                           :val (:component-file obj)}
                                          {:type :set
                                           :attr :component-root?
                                           :val (:component-root? obj)}
                                          {:type :set
                                           :attr :remote-synced?
                                           :val (:remote-synced? obj)}
                                          {:type :set
                                           :attr :shape-ref
                                           :val (:shape-ref obj)}
                                          {:type :set
                                           :attr :touched
                                           :val (:touched obj)}]})
                          shapes)]

        (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true}))))))

(defn nav-to-component-file
  [file-id]
  (us/assert ::us/uuid file-id)
  (ptk/reify ::nav-to-component-file
    ptk/WatchEvent
    (watch [_ state stream]
      (let [file (get-in state [:workspace-libraries file-id])
            pparams {:project-id (:project-id file)
                     :file-id (:id file)}
            qparams {:page-id (first (get-in file [:data :pages]))
                     :layout :assets}]
        (st/emit! (rt/nav-new-window :workspace pparams qparams))))))

(defn ext-library-changed
  [file-id modified-at revn changes]
  (us/assert ::us/uuid file-id)
  (us/assert ::cp/changes changes)
  (ptk/reify ::ext-library-changed
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update-in [:workspace-libraries file-id]
                     assoc :modified-at modified-at :revn revn)
          (d/update-in-when [:workspace-libraries file-id :data]
                            cp/process-changes changes)))))

(defn reset-component
  "Cancels all modifications in the shape with the given id, and all its children, in
  the current page. Set all attributes equal to the ones in the linked component,
  and untouched."
  [id]
  (us/assert ::us/uuid id)
  (ptk/reify ::reset-component
    ptk/WatchEvent
    (watch [_ state stream]
      (log/info :msg "RESET-COMPONENT of shape" :id (str id))
      (let [local-library (dwlh/get-local-file state)
            libraries     (dwlh/get-libraries state)
            container     (cp/get-container (get state :current-page-id)
                                            :page
                                            local-library)
            [rchanges uchanges]
            (dwlh/generate-sync-shape-direct container
                                             id
                                             local-library
                                             libraries
                                             true)]
        (log/debug :msg "RESET-COMPONENT finished" :js/rchanges (log-changes
                                                                  rchanges
                                                                  local-library))

        (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true}))))))

(defn update-component
  "Modify the component linked to the shape with the given id, in the
  current page, so that all attributes of its shapes are equal to the
  shape and its children. Also set all attributes of the shape
  untouched.

  NOTE: It's possible that the component to update is defined in an
  external library file, so this function may cause to modify a file
  different of that the one we are currently editing."
  [id]
  (us/assert ::us/uuid id)
  (ptk/reify ::update-component
    ptk/WatchEvent
    (watch [_ state stream]
      (log/info :msg "UPDATE-COMPONENT of shape" :id (str id))
      (let [page-id       (get state :current-page-id)
            local-library (dwlh/get-local-file state)
            libraries     (dwlh/get-libraries state)

            [rchanges uchanges]
            (dwlh/generate-sync-shape-inverse page-id
                                              id
                                              local-library
                                              libraries)

            container (cp/get-container page-id :page local-library)
            shape     (cp/get-shape container id)
            file-id   (:component-file shape)
            file      (dwlh/get-file state file-id)

            xf-filter (comp
                        (filter :local-change?)
                        (map #(dissoc % :local-change?)))

            local-rchanges (into [] xf-filter rchanges)
            local-uchanges (into [] xf-filter uchanges)

            xf-remove (comp
                        (remove :local-change?)
                        (map #(dissoc % :local-change?)))

            rchanges (into [] xf-remove rchanges)
            uchanges (into [] xf-remove uchanges)]

        (log/debug :msg "UPDATE-COMPONENT finished"
                   :js/local-rchanges (log-changes
                                        local-rchanges
                                        local-library)
                   :js/rchanges (log-changes
                                  rchanges
                                  file))

        (rx/of (when (seq local-rchanges)
                 (dwc/commit-changes local-rchanges local-uchanges
                                     {:commit-local? true
                                      :file-id (:id local-library)}))
               (when (seq rchanges)
                 (dwc/commit-changes rchanges uchanges
                                     {:commit-local? true
                                      :file-id file-id})))))))

(declare sync-file-2nd-stage)

(defn sync-file
  "Synchronize the given file from the given library. Walk through all
  shapes in all pages in the file that use some color, typography or
  component of the library, and copy the new values to the shapes. Do
  it also for shapes inside components of the local file library."
  [file-id library-id]
  (us/assert ::us/uuid file-id)
  (us/assert ::us/uuid library-id)
  (ptk/reify ::sync-file
    ptk/UpdateEvent
    (update [_ state]
      (if (not= library-id (:current-file-id state))
        (assoc-in state [:workspace-libraries library-id :synced-at] (dt/now))
        state))

    ptk/WatchEvent
    (watch [_ state stream]
      (log/info :msg "SYNC-FILE"
                :file (dwlh/pretty-file file-id state)
                :library (dwlh/pretty-file library-id state))
      (let [file            (dwlh/get-file state file-id)
            library-changes [(dwlh/generate-sync-library file-id :components library-id state)
                             (dwlh/generate-sync-library file-id :colors library-id state)
                             (dwlh/generate-sync-library file-id :typographies library-id state)]
            file-changes    [(dwlh/generate-sync-file file-id :components library-id state)
                             (dwlh/generate-sync-file file-id :colors library-id state)
                             (dwlh/generate-sync-file file-id :typographies library-id state)]

            xf-fcat  (comp (remove nil?) (map first) (mapcat identity))
            rchanges (d/concat []
                               (sequence xf-fcat library-changes)
                               (sequence xf-fcat file-changes))

            xf-scat  (comp (remove nil?) (map second) (mapcat identity))
            uchanges (d/concat []
                               (sequence xf-scat library-changes)
                               (sequence xf-scat file-changes))]

        (log/debug :msg "SYNC-FILE finished" :js/rchanges (log-changes
                                                            rchanges
                                                            file))
        (rx/concat
          (rx/of (dm/hide-tag :sync-dialog))
          (when rchanges
            (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true
                                                          :file-id file-id})))
          (when (not= file-id library-id)
            ;; When we have just updated the library file, give some time for the
            ;; update to finish, before marking this file as synced.
            ;; TODO: look for a more precise way of syncing this.
            ;; Maybe by using the stream (second argument passed to watch)
            ;; to wait for the corresponding changes-commited and then proced
            ;; with the :update-sync mutation.
            (rx/concat (rx/timer 3000)
                       (rp/mutation :update-sync
                                    {:file-id file-id
                                     :library-id library-id})))
          (when (some? library-changes)
            (rx/of (sync-file-2nd-stage file-id library-id))))))))

(defn sync-file-2nd-stage
  "If some components have been modified, we need to launch another synchronization
  to update the instances of the changed components."
  ;; TODO: this does not work if there are multiple nested components. Only the
  ;;       first level will be updated.
  ;;       To solve this properly, it would be better to launch another sync-file
  ;;       recursively. But for this not to cause an infinite loop, we need to
  ;;       implement updated-at at component level, to detect what components have
  ;;       not changed, and then not to apply sync and terminate the loop.
  [file-id library-id]
  (us/assert ::us/uuid file-id)
  (us/assert ::us/uuid library-id)
  (ptk/reify ::sync-file-2nd-stage
    ptk/WatchEvent
    (watch [_ state stream]
      (log/info :msg "SYNC-FILE (2nd stage)"
                :file (dwlh/pretty-file file-id state)
                :library (dwlh/pretty-file library-id state))
      (let [file                  (dwlh/get-file state file-id)
            [rchanges1 uchanges1] (dwlh/generate-sync-file file-id :components library-id state)
            [rchanges2 uchanges2] (dwlh/generate-sync-library file-id :components library-id state)
            rchanges (d/concat rchanges1 rchanges2)
            uchanges (d/concat uchanges1 uchanges2)]
        (when rchanges
          (log/debug :msg "SYNC-FILE (2nd stage) finished" :js/rchanges (log-changes
                                                                          rchanges
                                                                          file))
          (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true
                                                        :file-id file-id})))))))

(def ignore-sync
  (ptk/reify ::ignore-sync
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-file :ignore-sync-until] (dt/now)))

    ptk/WatchEvent
    (watch [_ state stream]
      (rp/mutation :ignore-sync
                   {:file-id (get-in state [:workspace-file :id])
                    :date (dt/now)}))))

(defn notify-sync-file
  [file-id]
  (us/assert ::us/uuid file-id)
  (ptk/reify ::notify-sync-file
    ptk/WatchEvent
    (watch [_ state stream]
      (let [libraries-need-sync (filter #(> (:modified-at %) (:synced-at %))
                                        (vals (get state :workspace-libraries)))
            do-update #(do (apply st/emit! (map (fn [library]
                                                  (sync-file (:current-file-id state)
                                                             (:id library)))
                                                libraries-need-sync))
                           (st/emit! dm/hide))
            do-dismiss #(do (st/emit! ignore-sync)
                            (st/emit! dm/hide))]
        (rx/of (dm/info-dialog
                 (tr "workspace.updates.there-are-updates")
                 :inline-actions
                 [{:label (tr "workspace.updates.update")
                   :callback do-update}
                  {:label (tr "workspace.updates.dismiss")
                   :callback do-dismiss}]
                 :sync-dialog))))))

