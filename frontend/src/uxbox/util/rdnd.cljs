;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.rdnd
  (:require [vendor.react-dnd]))

(def useDrop js/ReactDnd.useDrop)
(def useDrag js/ReactDnd.useDrag)

(def provider js/ReactDnd.DndProvider)
(def html5 js/ReactDnd.HTML5Backend)
