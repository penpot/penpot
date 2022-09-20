;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.dashboard.shortcuts
  (:require
   [app.main.data.dashboard :as dd]
   [app.main.data.shortcuts :as ds]
   [app.main.store :as st]))

(def shortcuts
  {:go-to-search       {:tooltip (ds/meta "F")
                        :command (ds/c-mod "f")
                        :subsections [:navigation-dashboard]
                        :fn #(st/emit! (dd/go-to-search))}

   :go-to-drafts       {:tooltip "G D"
                        :command "g d"
                        :subsections [:navigation-dashboard]
                        :fn #(st/emit! (dd/go-to-drafts))}

   :go-to-libs         {:tooltip "G L"
                        :command "g l"
                        :subsections [:navigation-dashboard]
                        :fn #(st/emit! (dd/go-to-libs))}
   
   :create-new-project {:tooltip "+"
                        :command "+"
                        :subsections [:general-dashboard]
                        :fn #(st/emit! (dd/create-element))}})

(defn get-tooltip [shortcut]
  (assert (contains? shortcuts shortcut) (str shortcut))
  (get-in shortcuts [shortcut :tooltip]))
