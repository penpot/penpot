;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.ui.workspace.images
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.locales :as t :refer (tr)]
            [uxbox.state :as st]
            [uxbox.rstore :as rs]
            [uxbox.library :as library]
            [uxbox.data.dashboard :as dd]
            [uxbox.data.lightbox :as udl]
            [uxbox.data.images :as di]
            [uxbox.ui.icons :as i]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.lightbox :as lbx]
            [uxbox.ui.keyboard :as k]
            [uxbox.ui.library-bar :as ui.library-bar]
            [uxbox.ui.dashboard.header :refer (header)]
            [uxbox.util.lens :as ul]
            [uxbox.util.dom :as dom]))

;; --- Helpers & Constants

(defn- new-image-lightbox-render
  [own]
  (html
   [:div.lightbox-body
    [:h3 "New image"]
    [:div.row-flex
     [:div.lightbox-big-btn
      [:span.big-svg i/shapes]
      [:span.text "Go to workspace"]]
     [:div.lightbox-big-btn
      {:on-click #(dom/click (dom/get-element-by-class "upload-image-input"))}
      [:span.big-svg.upload i/exit]
      [:span.text "Upload file"]
      [:input.upload-image-input {:style {:display "none"}
                                  :type "file"
                                  :on-change #(rs/emit! (di/create-images nil (dom/get-event-files %)))}]]]
    [:a.close {:href "#"
               :on-click #(do (dom/prevent-default %)
                              (udl/close!))}
     i/close]]))

(def ^:private new-image-lightbox
  (mx/component
   {:render new-image-lightbox-render
    :name "new-image-lightbox"}))

(defmethod lbx/render-lightbox :new-image
  [_]
  (new-image-lightbox))
