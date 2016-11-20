;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.core
  (:require [clojure.walk :as walk]
            [cuerdas.core :as str]
            [uxbox.util.exceptions :as ex]))

(defmulti novelty :type)

(defmulti query :type)

(defmethod novelty :default
  [{:keys [type] :as data}]
  (ex/raise :code ::not-implemented
            :message-category :novelty
            :message-type type))

(defmethod query :default
  [{:keys [type] :as data}]
  (ex/raise :code ::not-implemented
            :message-category :query
            :message-type type))

