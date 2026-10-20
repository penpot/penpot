;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.dashboard-import-linking-test
  "The links the import wizard creates for a file of the package whose
  library the auto-link could not resolve. The resolution map comes from the
  job result, so the type of its keys is not something the wizard can rely
  on: the file id of a link must come from the entry and never from the key
  of the map."
  (:require
   [app.common.uuid :as uuid]
   [app.main.ui.dashboard.import :as import]
   [cljs.test :as t :include-macros true]))

(def ^:private pending-library-links
  @#'import/pending-library-links)

(defn- pending-entry
  "One library of the package the user has to resolve, with a candidate."
  [library-id]
  {:id         library-id
   :name       "Tokens"
   :candidates [{:id           (uuid/next)
                 :name         "Tokens"
                 :project-id   (uuid/next)
                 :project-name "Design system"}]})

(defn- file-resolution
  "One file of a resolution, with a single library pending resolution."
  [file-id library-id]
  {:id      file-id
   :name    "card.penpot"
   :done    []
   :pending [(pending-entry library-id)]})

(t/deftest the-file-id-of-a-link-comes-from-the-entry-and-not-from-the-key
  (let [file-id (uuid/next)
        old-lib (uuid/next)
        new-lib (uuid/next)
        ;; the shape a result without a declared schema has: the key of the
        ;; map is a keyword and the entry carries the same id as text
        resolution {(keyword (str file-id)) (file-resolution (str file-id) (str old-lib))}
        selection  {(str file-id) {(str old-lib) (str new-lib)}}]

    (t/is (= [{:file-id (str file-id) :library-id (str new-lib)}]
             (pending-library-links resolution selection)))))

(t/deftest the-links-are-the-same-with-a-typed-resolution
  (let [file-id (uuid/next)
        old-lib (uuid/next)
        new-lib (uuid/next)
        resolution {file-id (file-resolution file-id old-lib)}
        selection  {file-id {old-lib new-lib}}]

    (t/is (= [{:file-id file-id :library-id new-lib}]
             (pending-library-links resolution selection)))))

(t/deftest a-file-with-several-pending-libraries-creates-one-link-each
  (let [file-id (uuid/next)
        lib-a   (uuid/next)
        lib-b   (uuid/next)
        new-a   (uuid/next)
        new-b   (uuid/next)
        resolution {file-id {:id      file-id
                             :name    "card.penpot"
                             :done    []
                             :pending [(pending-entry lib-a)
                                       (pending-entry lib-b)]}}
        selection  {file-id {lib-a new-a
                             lib-b new-b}}]

    (t/testing "both entries the user resolved become a link"
      (t/is (= #{{:file-id file-id :library-id new-a}
                 {:file-id file-id :library-id new-b}}
               (set (pending-library-links resolution selection)))))))

(t/deftest a-partial-selection-creates-only-the-chosen-link
  (let [file-id (uuid/next)
        lib-a   (uuid/next)
        lib-b   (uuid/next)
        new-a   (uuid/next)
        resolution {file-id {:id      file-id
                             :name    "card.penpot"
                             :done    []
                             :pending [(pending-entry lib-a)
                                       (pending-entry lib-b)]}}
        selection  {file-id {lib-a new-a}}]

    (t/testing "the entry with no candidate chosen emits nothing"
      (t/is (= [{:file-id file-id :library-id new-a}]
               (pending-library-links resolution selection))))))

(t/deftest a-done-entry-creates-no-link
  (let [file-id (uuid/next)
        library (uuid/next)]

    (t/testing "a file whose libraries were auto-linked has nothing to emit"
      (t/is (= [] (pending-library-links
                   {file-id {:id      file-id
                             :name    "card.penpot"
                             :done    [{:id        library
                                        :name      "Tokens"
                                        :linked-to library}]
                             :pending []}}
                   {file-id {library (uuid/next)}}))))))

(t/deftest pending-entries-without-a-selection-create-no-link
  (let [file-id (uuid/next)
        old-lib (uuid/next)]

    (t/testing "an entry the user skipped creates nothing"
      (t/is (= [] (pending-library-links
                   {file-id (file-resolution file-id old-lib)}
                   {file-id {}}))))

    (t/testing "a file with nothing pending creates nothing"
      (t/is (= [] (pending-library-links
                   {file-id {:id      file-id
                             :name    "card.penpot"
                             :done    []
                             :pending []}}
                   {file-id {old-lib (uuid/next)}}))))

    (t/testing "an empty resolution creates nothing"
      (t/is (= [] (pending-library-links {} {}))))))
