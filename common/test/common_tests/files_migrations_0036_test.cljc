;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.files-migrations-0036-test
  (:require
   [app.common.files.migrations :as cfm]
   [app.common.types.file :as ctf]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

;; ======================================================================
;; 0036-clear-nil-keys-in-objects
;; Soft-delete can leave a nil key in a deleted component's `:objects`
;; (`{nil {:selrect nil :points []}}`). That fails the file schema on
;; duplicate-file (#12336). `legacy-36` already stripped nil keys once,
;; but files that already list it as applied can pick the key up again.
;; This migration re-runs the cleanup.
;; ======================================================================

(def ^:private migration "0036-clear-nil-keys-in-objects")

(def ^:private nil-placeholder
  {:selrect nil :points []})

(defn- root
  [children]
  (cts/setup-shape {:id uuid/zero
                    :type :frame
                    :name "Root"
                    :shapes children}))

(t/deftest removes-nil-key-from-component-objects
  (let [component-id (uuid/next)
        data         {:components
                      {component-id
                       {:id component-id
                        :name "Trashed"
                        :path ""
                        :deleted true
                        :main-instance-id (uuid/next)
                        :main-instance-page (uuid/next)
                        :objects {nil nil-placeholder}}}}
        migrated     (cfm/migrate-data data migration)
        objects'     (get-in migrated [:components component-id :objects])]

    (t/is (not (contains? objects' nil))
          "nil key is removed from the deleted component :objects")
    (t/is (= {} objects')
          "only the nil placeholder was present, so objects is empty")))

(t/deftest removes-nil-key-from-page-objects
  (let [page-id  (uuid/next)
        frame-id (uuid/next)
        frame    (cts/setup-shape {:id frame-id :type :frame :name "Frame" :shapes []})
        data     {:pages-index
                  {page-id
                   {:id page-id
                    :name "Page"
                    :objects {uuid/zero (root [frame-id])
                              frame-id frame
                              nil nil-placeholder}}}}
        migrated (cfm/migrate-data data migration)
        objects' (get-in migrated [:pages-index page-id :objects])]

    (t/is (not (contains? objects' nil))
          "nil key is removed from the page :objects")
    (t/is (some? (get objects' frame-id))
          "real shapes are kept")))

(t/deftest migrated-file-data-passes-schema
  ;; Same failure mode duplicate-file hits: full schema check after migrate.
  (let [file-id      (uuid/next)
        page-id      (uuid/next)
        component-id (uuid/next)
        data         (-> (ctf/make-file-data file-id page-id)
                         (assoc-in [:components component-id]
                                   {:id component-id
                                    :name "Trashed"
                                    :path ""
                                    :deleted true
                                    :main-instance-id (uuid/next)
                                    :main-instance-page page-id
                                    :objects {nil nil-placeholder}}))]

    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (ctf/check-file-data data))
          "corrupt trash objects fail the file schema")

    (let [data' (cfm/migrate-data data migration)]
      (t/is (not (contains? (get-in data' [:components component-id :objects]) nil)))
      (t/is (= data' (ctf/check-file-data data'))
            "after clearing nil keys the file data passes the schema"))))
