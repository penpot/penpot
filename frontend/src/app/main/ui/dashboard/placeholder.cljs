;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.placeholder
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.event :as ev]
   [app.main.store :as st]
   [app.main.ui.dashboard.import :as udi]
   [app.main.ui.ds.product.empty-placeholder :refer [empty-placeholder*]]
   [app.main.ui.ds.product.loader :refer [loader*]]
   [app.main.ui.icons :as deprecated-icon]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [okulary.core :as l]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(mf/defc empty-project-placeholder*
  {::mf/private true}
  [{:keys [on-create on-finish-import project-id]}]
  (let [file-input       (mf/use-ref nil)

        on-add-library
        (mf/use-fn
         (fn [_]
           (st/emit! (ptk/event ::ev/event {::ev/name "explore-libraries-click"
                                            ::ev/origin "dashboard"
                                            :section "empty-placeholder-projects"}))
           (dom/open-new-window "https://penpot.app/penpothub/libraries-templates")))

        on-import
        (mf/use-fn #(dom/click! (mf/ref-val file-input)))]

    [:div {:class (stl/css :empty-project-container)}
     [:div {:class (stl/css :empty-project-card)
            :on-click on-create
            :title (tr "dashboard.add-file")}
      [:div {:class (stl/css :empty-project-card-title)}
       (tr "dashboard.empty-project.create")]
      [:div {:class (stl/css :empty-project-card-subtitle)}
       (tr "dashboard.empty-project.start")]]

     [:div {:class (stl/css :empty-project-card)
            :on-click on-import
            :title (tr "dashboard.empty-project.import")}
      [:div {:class (stl/css :empty-project-card-title)}
       (tr "dashboard.empty-project.import")]
      [:div {:class (stl/css :empty-project-card-subtitle)}
       (tr "dashboard.empty-project.import-penpot")]]

     [:div {:class (stl/css :empty-project-card)
            :on-click on-add-library
            :title (tr "dashboard.empty-project.go-to-libraries")}
      [:div {:class (stl/css :empty-project-card-title)}
       (tr "dashboard.empty-project.add-library")]
      [:div {:class (stl/css :empty-project-card-subtitle)}
       (tr "dashboard.empty-project.explore")]]

     [:& udi/import-form {:ref file-input
                          :project-id project-id
                          :on-finish-import on-finish-import}]]))

(defn- make-has-other-files-or-projects-ref
  "Return a ref that resolves to true or false if there are at least some
  file or some project (a part of the default) exists; this determines
  if we need to show a complete placeholder or the small one."
  [team-id]
  (l/derived (fn [state]
               (or (let [projects (get state :projects)]
                     (some (fn [[_ project]]
                             (and (= (:team-id project) team-id)
                                  (not (:is-default project))))
                           projects))
                   (let [files (get state :files)]
                     (some (fn [[_ file]]
                             (= (:team-id file) team-id))
                           files))))
             st/state))

(mf/defc empty-grid-placeholder*
  [{:keys [is-dragging limit origin create-fn can-edit team-id project-id on-finish-import]}]
  (let [on-click
        (mf/use-fn
         (mf/deps create-fn)
         (fn [_]
           (create-fn "dashboard:empty-folder-placeholder")))

        show-text*     (mf/use-state nil)
        show-text?     (deref show-text*)

        on-mouse-enter (mf/use-fn #(reset! show-text* true))
        on-mouse-leave (mf/use-fn #(reset! show-text* nil))

        has-other*     (mf/with-memo [team-id]
                         (make-has-other-files-or-projects-ref team-id))
        has-other?     (mf/deref has-other*)]

    (cond
      (true? is-dragging)
      [:ul
       {:class (stl/css :grid-row :no-wrap)
        :style {:grid-template-columns (str "repeat(" limit ", 1fr)")}}
       [:li {:class (stl/css :grid-item :grid-empty-placeholder :dragged)}]]

      (= :libraries origin)
      [:> empty-placeholder*
       {:title (tr "dashboard.empty-placeholder-libraries-title")
        :type 2
        :subtitle (when-not can-edit
                    (tr "dashboard.empty-placeholder-libraries-subtitle-viewer-role"))
        :class (stl/css :empty-placeholder-libraries)}

       (when can-edit
         [:> i18n/tr-html* {:content (tr "dashboard.empty-placeholder-libraries")
                            :class (stl/css :placeholder-markdown)
                            :tag-name "span"}])]

      :else
      (if-not has-other?
        [:> empty-project-placeholder*
         {:on-create on-click
          :on-finish-import on-finish-import
          :project-id project-id}]
        [:div {:class (stl/css :grid-empty-placeholder)}
         [:button {:class (stl/css :create-new)
                   :on-click on-click
                   :on-mouse-enter on-mouse-enter
                   :on-mouse-leave on-mouse-leave}
          (if show-text?
            (tr "dashboard.empty-project.create")
            deprecated-icon/add)]]))))

(mf/defc loading-placeholder*
  []
  [:> loader*  {:width 32
                :title (tr "labels.loading")
                :class (stl/css :placeholder-loader)}
   [:span {:class (stl/css :placeholder-text)}
    (tr "dashboard.loading-files")]])
