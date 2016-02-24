(ns uxbox.ui.workspace.sidebar.document-history
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [cats.labs.lens :as l]
            [uxbox.locales :refer (tr)]
            [uxbox.router :as r]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.shapes :as shapes]
            [uxbox.library :as library]
            [uxbox.util.data :refer (read-string)]
            [uxbox.data.workspace :as dw]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.ui.icons :as i]
            [uxbox.ui.mixins :as mx]
            [uxbox.util.dom :as dom]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Component
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn document-history-toolbox-render
  [own]
  (let [workspace (rum/react wb/workspace-l)
        local (:rum/local own)
        section (:section @local :main)
        close #(rs/emit! (dw/toggle-flag :document-history))
        main? (= section :main)
        pinned? (= section :pinned)
        show-main #(swap! local assoc :section :main)
        show-pinned #(swap! local assoc :section :pinned)]
    (html
     [:div.document-history.tool-window
      [:div.tool-window-bar
       [:div.tool-window-icon i/undo-history]
       [:span (tr "ds.document-history")]
       [:div.tool-window-close {:on-click close} i/close]]
      [:div.tool-window-content
       [:ul.history-tabs
        [:li {:on-click show-main
              :class (when main? "selected")}
         "History"]
        [:li {:on-click show-pinned
              :class (when pinned? "selected")}
         "Pinned"]]
       (if (= section :pinned)
         [:ul.history-content
          [:li.current
           [:span "Current version"]]
          [:li
           [:span "Version 02/02/2016 12:33h"]
           [:div.page-actions
            [:a i/pencil]
            [:a i/trash]]]])
       (if (= section :main)
         [:ul.history-content
          [:li.current
           [:div.pin-icon i/pin]
           [:span "Current version"]]
          [:li
           [:div.pin-icon i/pin]
           [:span "Version 02/02/2016 12:33h"]]
          [:li
           [:div.pin-icon i/pin]
           [:span "Version 02/02/2016 12:33h"]]
          [:li
           [:div.pin-icon i/pin]
           [:span "Version 02/02/2016 12:33h"]]
          [:li
           [:div.pin-icon i/pin]
           [:span "Version 02/02/2016 12:33h"]]
          [:li
           [:div.pin-icon i/pin]
           [:span "Version 02/02/2016 12:33h"]]
          [:li
           [:div.pin-icon i/pin]
           [:span "Version 02/02/2016 12:33h"]]
          [:li
           [:div.pin-icon i/pin]
           [:span "Version 02/02/2016 12:33h"]]
          [:li
           [:div.pin-icon i/pin]
           [:span "Version 02/02/2016 12:33h"]]
          [:li
           [:div.pin-icon i/pin]
           [:span "Version 02/02/2016 12:33h"]]
          [:li
           [:div.pin-icon i/pin]
           [:span "Version 02/02/2016 12:33h"]]
          [:li
           [:div.pin-icon i/pin]
           [:span "Version 02/02/2016 12:33h"]]
          [:li
           [:div.pin-icon i/pin]
           [:span "Version 02/02/2016 12:33h"]]
          [:li
           [:div.pin-icon i/pin]
           [:span "Version 02/02/2016 12:33h"]]
          [:li
           [:div.pin-icon i/pin]
           [:span "Version 02/02/2016 12:33h"]]
          [:li
           [:div.pin-icon i/pin]
           [:span "Version 02/02/2016 12:33h"]]
          [:li
           [:div.pin-icon i/pin]
           [:span "Version 02/02/2016 12:33h"]]
          [:li
           [:div.pin-icon i/pin]
           [:span "Version 02/02/2016 12:33h"]]
          [:li
           [:div.pin-icon i/pin]
           [:span "Version 02/02/2016 12:33h"]]])]])))


(def ^:static document-history-toolbox
  (mx/component
   {:render document-history-toolbox-render
    :name "document-history-toolbox"
    :mixins [mx/static rum/reactive (mx/local)]}))
