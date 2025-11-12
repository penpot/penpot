;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.colorpicker.shortcuts
  (:require
   [app.main.data.shortcuts :as ds]
   [app.main.data.workspace.colors :as dwc]
   [app.main.data.workspace.shortcuts :as wsc]
   [app.main.store :as st]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shortcuts
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Shortcuts format https://github.com/ccampbell/mousetrap

(def shortcuts
  (merge
   wsc/shortcuts

   {:delete-stop  {:tooltip (ds/supr)
                   :command ["del" "backspace"]
                   :subsections [:edit]
                   :overwrite true
                   :fn #(st/emit! (dwc/remove-gradient-stop))}}))

