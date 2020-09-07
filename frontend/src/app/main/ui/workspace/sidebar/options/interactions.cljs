;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.interactions
  (:require
   [rumext.alpha :as mf]
   [app.common.data :as d]
   [app.common.pages-helpers :as cph]
   [app.main.data.workspace :as dw]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [t]]))

(mf/defc interactions-menu
  [{:keys [shape] :as props}]
  (let [locale      (mf/deref i18n/locale)
        objects     (deref refs/workspace-page-objects)
        interaction (first (:interactions shape))  ; TODO: in the
                                                   ; future we may
                                                   ; have several
                                                   ; interactions in
                                                   ; one shape

        destination (get objects (:destination interaction))
        frames      (mf/use-memo (mf/deps objects)
                                 #(cph/select-frames objects))

        show-frames-dropdown? (mf/use-state false)

        on-set-blur #(reset! show-frames-dropdown? false)
        on-navigate #(st/emit! (dw/select-shapes (d/ordered-set (:id destination))))

        on-select-destination
        (fn [dest]
          (if (nil? dest)
            (st/emit! (dw/update-shape (:id shape) {:interactions []}))
            (st/emit! (dw/update-shape (:id shape) {:interactions [{:event-type :click
                                                                    :action-type :navigate
                                                                    :destination dest}]}))))]

    (if (not shape)
      [:*
       [:div.interactions-help-icon i/interaction]
       [:div.interactions-help (t locale "workspace.options.select-a-shape")]
       [:div.interactions-help-icon i/play]
       [:div.interactions-help (t locale "workspace.options.use-play-button")]]

     [:div.element-set {:on-blur on-set-blur}
       [:div.element-set-title
        [:span (t locale "workspace.options.navigate-to")]]
       [:div.element-set-content
        [:div.row-flex
         [:div.custom-select.flex-grow {:on-click #(reset! show-frames-dropdown? true)}
          (if destination
            [:span (:name destination)]
            [:span (t locale "workspace.options.select-artboard")])
          [:span.dropdown-button i/arrow-down]
           [:& dropdown {:show @show-frames-dropdown?
                         :on-close #(reset! show-frames-dropdown? false)}
            [:ul.custom-select-dropdown
             [:li.dropdown-separator
              {:on-click #(on-select-destination nil)}
              (t locale "workspace.options.none")]

             (for [frame frames]
               (when (and (not= (:id frame) (:id shape)) ; A frame cannot navigate to itself
                          (not= (:id frame) (:frame-id shape))) ; nor a shape to its container frame
                 [:li {:key (:id frame)
                       :on-click #(on-select-destination (:id frame))}
                  (:name frame)]))]]]
         [:span.navigate-icon {on-click on-navigate} i/navigate]]]])))
