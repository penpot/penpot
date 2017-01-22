;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.migrations
  (:require [mount.core :as mount :refer (defstate)]
            [migrante.core :as mg :refer (defmigration)]
            [uxbox.db :as db]
            [uxbox.config :as cfg]
            [uxbox.util.template :as tmpl]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Migrations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmigration utils-0000
  "Create a initial version of txlog table."
  :up (mg/resource "migrations/0000.main.up.sql"))

(defmigration txlog-0001
  "Create a initial version of txlog table."
  :up (mg/resource "migrations/0001.txlog.up.sql"))

(defmigration auth-0002
  "Create initial auth related tables."
  :up (mg/resource "migrations/0002.auth.up.sql"))

(defmigration projects-0003
  "Create initial tables for projects."
  :up (mg/resource "migrations/0003.projects.up.sql"))

(defmigration pages-0004
  "Create initial tables for pages."
  :up (mg/resource "migrations/0004.pages.up.sql"))

(defmigration kvstore-0005
  "Create initial tables for kvstore."
  :up (mg/resource "migrations/0005.kvstore.up.sql"))

(defmigration emails-queue-0006
  "Create initial tables for emails queue."
  :up (mg/resource "migrations/0006.emails.up.sql"))

(defmigration images-0007
  "Create initial tables for image collections."
  :up (mg/resource "migrations/0007.images.up.sql"))

(defmigration icons-0008
  "Create initial tables for image collections."
  :up (mg/resource "migrations/0008.icons.up.sql"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Entry point
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def +migrations+
  {:name :uxbox-main
   :steps [[:0000 utils-0000]
           [:0001 txlog-0001]
           [:0002 auth-0002]
           [:0003 projects-0003]
           [:0004 pages-0004]
           [:0005 kvstore-0005]
           [:0006 emails-queue-0006]
           [:0007 images-0007]
           [:0008 icons-0008]]})

(defn- migrate
  []
  (let [options {:verbose (:migrations-verbose cfg/config true)}]
    (with-open [mctx (mg/context db/datasource options)]
      (mg/migrate mctx +migrations+)
      nil)))

(defstate migrations
  :start (migrate))
