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
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.undo :as dwu]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.libraries :refer [create-file-library-ref]]
   [app.main.ui.workspace.sidebar.assets.colors :refer [colors-section]]
   [app.main.ui.workspace.sidebar.assets.common :as cmm]
   [app.main.ui.workspace.sidebar.assets.components :refer [components-section]]
   [app.main.ui.workspace.sidebar.assets.graphics :refer [graphics-section]]
   [app.main.ui.workspace.sidebar.assets.typographies :refer [typographies-section]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.router :as rt]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def lens:open-status
  (l/derived (l/in [:workspace-assets :open-status]) st/state))

(def lens:selected
  (-> (l/in [:workspace-assets :selected])
      (l/derived st/state)))

(mf/defc file-library-title
  {::mf/wrap-props false}
  [{:keys [open? local? project-id file-id page-id file-name]}]
  (let [router     (mf/deref refs/router)
        url        (rt/resolve router :workspace
                               {:project-id project-id
                                :file-id file-id}
                               {:page-id page-id})
        toggle-open
        (mf/use-fn
         (mf/deps file-id open?)
         (fn []
           (st/emit! (dw/set-assets-section-open file-id :library (not open?)))))]
    [:div  {:class (stl/css-case :library-title true
                                 :open open?)}
     [:& title-bar {:collapsable    true
                    :collapsed      (not open?)
                    :all-clickable  true
                    :on-collapsed   toggle-open
                    :title          (if local?
                                      (mf/html [:div {:class (stl/css :special-title)}
                                                (tr "workspace.assets.local-library")])
                                      ;; Do we need to add shared info here?

                                      (mf/html [:div {:class (stl/css :special-title)}
                                                file-name]))}
      (when-not local?
        [:span {:title "Open library file"}
         [:a {:class (stl/css :file-link)
              :href (str "#" url)
              :target "_blank"
              :on-click dom/stop-propagation}
          i/open-link]])]]))

(mf/defc file-library-content
  {::mf/wrap-props false}
  [{:keys [file local? open-status-ref on-clear-selection]}]
  (let [components-v2      (mf/use-ctx ctx/components-v2)
        open-status        (mf/deref open-status-ref)

        file-id            (:id file)
        project-id         (:project-id file)

        filters            (mf/use-ctx cmm/assets-filters)
        filters-section    (:section filters)

        filters-term       (:term filters)
        filters-ordering   (:ordering filters)
        filters-list-style (:list-style filters)

        reverse-sort?      (= :desc filters-ordering)
        listing-thumbs?    (= :thumbs filters-list-style)

        library-ref        (mf/with-memo [file-id]
                             (create-file-library-ref file-id))

        library            (mf/deref library-ref)
        colors             (:colors library)
        components         (:components library)
        media              (:media library)
        typographies       (:typographies library)

        colors             (mf/with-memo [filters colors]
                             (cmm/apply-filters colors filters))
        components         (mf/with-memo [filters components]
                             (cmm/apply-filters components filters))
        media              (mf/with-memo [filters media]
                             (cmm/apply-filters media filters))
        typographies       (mf/with-memo [filters typographies]
                             (cmm/apply-filters typographies filters))

        show-components?   (and (or (= filters-section "all")
                                    (= filters-section "components"))
                                (or (pos? (count components))
                                    (str/empty? filters-term)))
        show-graphics?     (and (or (= filters-section "all")
                                    (= filters-section "graphics"))
                                (or (pos? (count media))
                                    (and (str/empty? filters-term)
                                         (not components-v2))))
        show-colors?       (and (or (= filters-section "all")
                                    (= filters-section "colors"))
                                (or (> (count colors) 0)
                                    (str/empty? filters-term)))
        show-typography?   (and (or (= filters-section "all")
                                    (= filters-section "typographies"))
                                (or (pos? (count typographies))
                                    (str/empty? filters-term)))

        selected-lens      (mf/with-memo [file-id]
                             (-> (l/key file-id)
                                 (l/derived lens:selected)))

        selected           (mf/deref selected-lens)

        has-term?                (not ^boolean (str/empty? filters-term))
        force-open-components?   (when ^boolean has-term? (> 60 (count components)))
        force-open-colors?       (when ^boolean has-term? (> 60 (count colors)))
        force-open-graphics?     (when ^boolean has-term? (> 60 (count media)))
        force-open-typographies? (when ^boolean has-term? (> 60 (count typographies)))

        extend-selected
        (fn [type asset-groups asset-id]
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

        on-asset-click
        (mf/use-fn
         (mf/deps file-id extend-selected)
         (fn [asset-type asset-groups asset-id default-click event]
           (cond
             (kbd/mod? event)
             (do
               (dom/stop-propagation event)
               (st/emit! (dw/toggle-selected-assets file-id asset-id asset-type)))

             (kbd/shift? event)
             (do
               (dom/stop-propagation event)
               (extend-selected asset-type asset-groups asset-id))

             :else
             (when default-click
               (default-click event)))))

        on-component-click
        (mf/use-fn (mf/deps on-asset-click) (partial on-asset-click :components))

        on-graphics-click
        (mf/use-fn (mf/deps on-asset-click) (partial on-asset-click :graphics))

        on-colors-click
        (mf/use-fn (mf/deps on-asset-click) (partial on-asset-click :colors))

        on-typography-click
        (mf/use-fn (mf/deps on-asset-click) (partial on-asset-click :typographies))

        on-assets-delete
        (mf/use-fn
         (mf/deps selected file-id)
         (fn []
           (let [undo-id (js/Symbol)]
             (st/emit! (dwu/start-undo-transaction undo-id))
             (run! st/emit! (map #(dwl/delete-component {:id %})
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
     (when ^boolean show-components?
       [:& components-section
        {:file-id file-id
         :local? local?
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
         :on-clear-selection on-clear-selection}])

     (when ^boolean show-graphics?
       [:& graphics-section
        {:file-id file-id
         :project-id project-id
         :local? local?
         :objects media
         :listing-thumbs? listing-thumbs?
         :open? (or ^boolean force-open-graphics?
                    ^boolean (get open-status :graphics false))
         :force-open? force-open-graphics?
         :open-status-ref open-status-ref
         :reverse-sort? reverse-sort?
         :selected selected
         :on-asset-click on-graphics-click
         :on-assets-delete on-assets-delete
         :on-clear-selection on-clear-selection}])

     (when ^boolean show-colors?
       [:& colors-section
        {:file-id file-id
         :local? local?
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
         :local? local?
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
                (not ^boolean show-graphics?)
                (not ^boolean show-colors?)
                (not ^boolean show-typography?))
       [:div  {:class (stl/css :asset-title)}
        [:span {:class (stl/css :no-found-icon)}
         i/search]
        [:span {:class (stl/css :no-found-text)}
         (tr "workspace.assets.not-found")]])]))


(mf/defc file-library
  {::mf/wrap-props false}
  [{:keys [file local? default-open? filters]}]
  (let [file-id         (:id file)
        file-name       (:name file)
        shared?         (:is-shared file)
        project-id      (:project-id file)
        page-id         (dm/get-in file [:data :pages 0])

        open-status-ref (mf/with-memo [file-id]
                          (-> (l/key file-id)
                              (l/derived lens:open-status)))
        open-status      (mf/deref open-status-ref)
        open?            (d/nilv (:library open-status) default-open?)

        unselect-all
        (mf/use-fn
         (mf/deps file-id)
         (fn []
           (st/emit! (dw/unselect-all-assets file-id))))]
    [:div {:class (stl/css :tool-window)
           :on-context-menu dom/prevent-default
           :on-click unselect-all}
     [:& file-library-title
      {:project-id project-id
       :file-id file-id
       :page-id page-id
       :file-name file-name
       :open? open?
       :local? local?
       :shared? shared?}]
     (when ^boolean open?
       [:& file-library-content
        {:file file
         :local? local?
         :filters filters
         :on-clear-selection unselect-all
         :open-status-ref open-status-ref}])]))
