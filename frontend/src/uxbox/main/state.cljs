;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.state
  (:require [uxbox.builtins.colors :as colors]
            [uxbox.library.icons :as icons]
            [uxbox.util.storage :refer [storage]]))

(defn initial-state
  []
  {:dashboard {:project-order :name
               :project-filter ""
               :images-order :name
               :images-filter ""}
   :route nil
   :auth (:auth storage nil)
   :clipboard #queue []
   :undo {}
   :profile nil
   :workspace nil
   :images-collections nil
   :images nil
   :icons-collections nil
   :icons nil
   :colors-collections colors/collections
   :shapes nil
   :projects nil
   :pages nil})
