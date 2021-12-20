;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.dashboard.shortcuts
  (:require
   [app.main.data.dashboard :as dd]
   [app.main.data.shortcuts :as ds]
   [app.main.store :as st]))

(def shortcuts
  {:go-to-search       {:tooltip (ds/meta "F")
                        :command (ds/c-mod "f")
                        :fn (st/emitf (dd/go-to-search))}

   :go-to-drafts       {:tooltip "G D"
                        :command "g d"
                        :fn (st/emitf (dd/go-to-drafts))}

   :go-to-libs         {:tooltip "G L"
                        :command "g l"
                        :fn (st/emitf (dd/go-to-libs))}
   
   :create-new-project {:tooltip "+"
                        :command "+"
                        :fn (st/emitf (dd/create-element))}})

(defn get-tooltip [shortcut]
  (assert (contains? shortcuts shortcut) (str shortcut))
  (get-in shortcuts [shortcut :tooltip]))
