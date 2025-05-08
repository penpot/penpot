; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.chat-button
  (:require-macros [app.main.style :as stl])
  (:require
   ["@inkeep/cxkit-react" :refer (InkeepChatButton)]
   [app.main.ui.icons :as i]
   [rumext.v2 :as mf]))

(def ^:private inkeep-config
  #js {:baseSettings
       #js {:apiKey ""
            :theme #js {:styles
                        #js [#js {:key "3"
                                  :type "style"
                                  :value ".ikp-chat-button__container { position: relative; top: 0; left: 0; opacity:0; }"}]}}
       :aiChatSettings
       #js {:getHelpOptions
            #js [#js {:name "Ask the community"
                      :icon #js {:builtIn "IoHelpBuoyOutline"}
                      :action #js {:type "open_link"
                                   :url "https://community.penpot.app"}}
                 #js {:name "Contact us"
                      :icon #js {:builtIn "IoMail"}
                      :action #js {:type "open_link"
                                   :url "mailto:hello@getstartedwithpenpot.com"}}]}})


(mf/defc chat-button* []
  ;; TODO: Show when accepted cookies and when apiKey is set
  (when false
  [:button {:class (stl/css :help-btn)}
   i/help
   [:> InkeepChatButton inkeep-config]]))
