;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.assets.file-library
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.variant :as cfv]
   [app.common.types.component :as ctc]
   [app.common.types.components-list :as ctkl]
   [app.main.data.event :as ev]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.undo :as dwu]
   [app.main.refs :as refs]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as deprecated-icon]
   [app.main.ui.workspace.sidebar.assets.colors :refer [colors-section]]
   [app.main.ui.workspace.sidebar.assets.common :as cmm]
   [app.main.ui.workspace.sidebar.assets.components :refer [components-section]]
   [app.main.ui.workspace.sidebar.assets.typographies :refer [typographies-section]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; REFS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private ref:open-status
  (l/derived (l/in [:workspace-assets :open-status]) st/state))

(def ^:private ref:selected
  (-> (l/in [:workspace-assets :selected])
      (l/derived st/state)))

(defn- create-file-ref
  [library-id]
  (l/derived (fn [state]
               (dm/get-in state [:files library-id :data]))
             st/state))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; LOCAL HELPER HOOKS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- use-library-ref
  [file-id]
  (let [library-ref (mf/with-memo [file-id]
                      (create-file-ref file-id))]
    (mf/deref library-ref)))

(defn- use-selected
  "Returns the currently selected assets set on the library"
  [file-id]
  (let [selected-ref
        (mf/with-memo [file-id]
          (-> (l/key file-id)
              (l/derived ref:selected)))]
    (mf/deref selected-ref)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; COMPONENTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(mf/defc file-library-title*
  {::mf/private true}
  [{:keys [is-open is-local file-id page-id file-name]}]
  (let [router     (mf/deref refs/router)
        team-id    (mf/use-ctx ctx/current-team-id)
        url        (rt/resolve router :workspace
                               {:team-id team-id
                                :file-id file-id
                                :page-id page-id})
        toggle-open
        (mf/use-fn
         (mf/deps file-id is-open)
         (fn []
           (st/emit! (dw/set-assets-section-open file-id :library (not is-open)))))

        on-click
        (mf/use-fn
         (fn [ev]
           (dom/stop-propagation ev)
           (st/emit! (ptk/data-event ::ev/event {::ev/name "navigate-to-library-file"}))))]

    [:div {:class (stl/css-case
                   :library-title true
                   :open is-open)}
     [:> title-bar* {:collapsable    true
                     :collapsed      (not is-open)
                     :all-clickable  true
                     :on-collapsed   toggle-open
                     :title          (if is-local
                                       (mf/html [:div {:class (stl/css :special-title)}
                                                 (tr "workspace.assets.local-library")])
                                      ;; Do we need to add shared info here?
                                       (mf/html [:div {:class (stl/css :special-title)}
                                                 file-name]))}
      (when-not ^boolean is-local
        [:span {:title (tr "workspace.assets.open-library")}
         [:a {:class (stl/css :file-link)
              :href (str "#" url)
              :target "_blank"
              :on-click on-click}
          deprecated-icon/open-link]])]]))

(defn- extend-selected
  [selected type asset-groups asset-id file-id]
  (letfn [(flatten-groups [groups]
            (reduce concat [(get groups "" [])
                            (into []
                                  (->> (filter #(seq (first %)) groups)
                                       (map second)
                                       (mapcat flatten-groups)))]))]

    (let [selected' (get selected type)]
      (if (zero? (count selected'))
        (st/emit! (dw/select-single-asset file-id asset-id type))
        (let [all-assets  (flatten-groups asset-groups)
              click-index (d/index-of-pred all-assets #(= (:id %) asset-id))
              first-index (->> (get selected type)
                               (map (fn [asset] (d/index-of-pred all-assets #(= (:id %) asset))))
                               (sort)
                               (first))

              min-index   (min first-index click-index)
              max-index   (max first-index click-index)
              ids         (->> (d/enumerate all-assets)
                               (into #{} (comp (filter #(<= min-index (first %) max-index))
                                               (map (comp :id second)))))]

          (st/emit! (dw/select-assets file-id ids type)))))))

(mf/defc file-library-content*
  {::mf/private true}
  [{:keys [file is-local is-loaded open-status-ref on-clear-selection filters colors typographies components count-variants]}]
  (let [open-status       (mf/deref open-status-ref)

        file-id           (:id file)

        filters-section   (:section filters)
        has-filters-term? (not ^boolean (str/empty? (:term filters)))

        reverse-sort?     (= :desc (:ordering filters))
        listing-thumbs?   (= :thumbs (:list-style filters))

        selected          (use-selected file-id)

        show-components?
        (and (or (= filters-section "all")
                 (= filters-section "components"))
             (or (pos? (count components))
                 (not has-filters-term?)))

        show-colors?
        (and (or (= filters-section "all")
                 (= filters-section "colors"))
             (or (> (count colors) 0)
                 (not has-filters-term?)))

        show-typography?
        (and (or (= filters-section "all")
                 (= filters-section "typographies"))
             (or (pos? (count typographies))
                 (not has-filters-term?)))

        force-open-components?
        (when ^boolean has-filters-term? (> 60 (count components)))

        force-open-colors?
        (when ^boolean has-filters-term? (> 60 (count colors)))

        force-open-typographies?
        (when ^boolean has-filters-term? (> 60 (count typographies)))

        on-asset-click
        (mf/use-fn
         (mf/deps file-id selected)
         (fn [asset-type asset-groups event asset-id]
           (cond
             (kbd/mod? event)
             (do
               (dom/stop-propagation event)
               (st/emit! (dw/toggle-selected-assets file-id asset-id asset-type))
               true)

             (kbd/shift? event)
             (do
               (dom/stop-propagation event)
               (extend-selected selected asset-type asset-groups asset-id file-id)
               true))))

        on-component-click
        (mf/use-fn (mf/deps on-asset-click) (partial on-asset-click :components))

        on-colors-click
        (mf/use-fn (mf/deps on-asset-click) (partial on-asset-click :colors))

        on-typography-click
        (mf/use-fn (mf/deps on-asset-click) (partial on-asset-click :typographies))

        delete-component
        (mf/use-fn
         (mf/deps components)
         (fn [component-id]
           (let [component (some #(when (= (:id %) component-id) %) components)]
             (if (ctc/is-variant? component)
               ;; If the component is a variant, delete its variant container
               (dwsh/delete-shapes (:main-instance-page component) #{(:variant-id component)})
               (dwl/delete-component {:id component-id})))))

        on-assets-delete
        (mf/use-fn
         (mf/deps selected file-id)
         (fn []
           (let [undo-id (js/Symbol)]
             (st/emit! (dwu/start-undo-transaction undo-id))
             (run! st/emit! (map delete-component
                                 (:components selected)))
             (run! st/emit! (map #(dwl/delete-media {:id %})
                                 (:graphics selected)))
             (run! st/emit! (map #(dwl/delete-color {:id %})
                                 (:colors selected)))
             (run! st/emit! (map #(dwl/delete-typography %)
                                 (:typographies selected)))

             (when (or (seq (:components selected))
                       (seq (:colors selected))
                       (seq (:typographies selected)))
               (st/emit! (dwl/sync-file file-id file-id)))

             (st/emit! (dwu/commit-undo-transaction undo-id)))))]

    [:div {:class (stl/css :library-content)}
     (if-not is-loaded
       [:span {:class (stl/css :loading)} (tr "labels.loading")]
       [:*
        (when ^boolean show-components?
          [:& components-section
           {:file-id file-id
            :is-local is-local
            :components components
            :listing-thumbs? listing-thumbs?
            :open? (or ^boolean force-open-components?
                       ^boolean (get open-status :components false))
            :force-open? force-open-components?
            :open-status-ref open-status-ref
            :reverse-sort? reverse-sort?
            :selected selected
            :on-asset-click on-component-click
            :on-assets-delete on-assets-delete
            :on-clear-selection on-clear-selection
            :delete-component delete-component
            :count-variants count-variants}])

        (when ^boolean show-colors?
          [:& colors-section
           {:file-id file-id
            :local? is-local
            :colors colors
            :open? (or ^boolean force-open-colors?
                       ^boolean (get open-status :colors false))
            :force-open? force-open-colors?
            :open-status-ref open-status-ref
            :reverse-sort? reverse-sort?
            :selected selected
            :on-asset-click on-colors-click
            :on-assets-delete on-assets-delete
            :on-clear-selection on-clear-selection}])

        (when ^boolean show-typography?
          [:& typographies-section
           {:file file
            :file-id (:id file)
            :local? is-local
            :typographies typographies
            :open? (or ^boolean force-open-typographies?
                       ^boolean (get open-status :typographies false))
            :force-open? force-open-typographies?
            :open-status-ref open-status-ref
            :reverse-sort? reverse-sort?
            :selected selected
            :on-asset-click on-typography-click
            :on-assets-delete on-assets-delete
            :on-clear-selection on-clear-selection}])

        (when (and (not ^boolean show-components?)
                   (not ^boolean show-colors?)
                   (not ^boolean show-typography?))
          [:div  {:class (stl/css :asset-title)}
           [:span {:class (stl/css :no-found-icon)}
            deprecated-icon/search]
           [:span {:class (stl/css :no-found-text)}
            (tr "workspace.assets.not-found")]])])]))

(mf/defc file-library*
  [{:keys [file is-local is-default-open filters]}]
  (let [file-id      (:id file)
        file-name    (:name file)
        page-id      (dm/get-in file [:data :pages 0])

        library      (use-library-ref file-id)

        colors       (:colors library)
        typographies (:typographies library)

        filters-term (:term filters)
        is-loaded    (some? library)

        filtered-colors
        (mf/with-memo [filters colors]
          (-> (vals colors)
              (cmm/apply-filters filters)))

        filtered-components
        (mf/with-memo [filters library]
          (as-> (into [] (ctkl/components-seq library)) $
            (cmm/apply-filters $ filters)
            (remove #(cfv/is-secondary-variant? % library) $)))

        filtered-typographies
        (mf/with-memo [filters typographies]
          (-> (vals typographies)
              (cmm/apply-filters filters)))

        open-status-ref
        (mf/with-memo [file-id]
          (-> (l/key file-id)
              (l/derived ref:open-status)))

        open-status
        (mf/deref open-status-ref)

        force-lib-open?
        (and (not (str/blank? filters-term))
             (or (> 60 (count filtered-colors))
                 (> 60 (count filtered-components))
                 (> 60 (count filtered-typographies))))

        open?
        (if (false? (:library open-status))
          ;; if the user has closed it specifically, respect that
          false
          (or force-lib-open?
              (d/nilv (:library open-status) is-default-open)))

        unselect-all
        (mf/use-fn
         (mf/deps file-id)
         (fn []
           (st/emit! (dw/unselect-all-assets file-id))))

        count-variants
        (mf/use-fn
         (mf/deps library)
         (fn [variant-id]
           (->> (ctkl/components-seq library)
                (filterv #(= variant-id (:variant-id %)))
                count)))]

    [:div {:class (stl/css :tool-window)
           :on-context-menu dom/prevent-default
           :on-click unselect-all}

     [:> file-library-title*
      {:file-id file-id
       :page-id page-id
       :file-name file-name
       :is-open open?
       :is-local is-local}]

     (when ^boolean open?
       [:> file-library-content*
        {:file file
         :is-local is-local
         :is-loaded is-loaded
         :filters filters
         :colors filtered-colors
         :components filtered-components
         :typographies filtered-typographies
         :on-clear-selection unselect-all
         :open-status-ref open-status-ref
         :count-variants count-variants}])]))
