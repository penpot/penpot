;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.rdnd
  (:require
   ["react-dnd/dist/esm/hooks" :as hooks]
   ["react-dnd/dist/esm/common" :as common]
   ["react-dnd-html5-backend" :as backend]))

(def useDrop hooks/useDrop)
(def useDrag hooks/useDrag)
(def provider common/DndProvider)
(def html5 backend/default)
