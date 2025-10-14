;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.logic.token-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.tokens :as clt]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.tokens :as tht]
   [app.common.types.tokens-lib :as ctob]
   [app.common.uuid :as uuid]
   [clojure.datafy :refer [datafy]]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(defn- setup-file [lib-fn]
  (-> (thf/sample-file :file1)
      (tht/add-tokens-lib)
      (tht/update-tokens-lib lib-fn)))

(t/deftest generate-toggle-token-set-test
  (t/testing "toggling an active set will switch to hidden theme without user sets"
    (let [file (setup-file #(-> %
                                (ctob/add-set (ctob/make-token-set :name "foo/bar"))
                                (ctob/add-theme (ctob/make-token-theme :name "theme"
                                                                       :sets #{"foo/bar"}))
                                (ctob/set-active-themes #{"/theme"})))
          changes (-> (pcb/empty-changes)
                      (pcb/with-library-data (:data file))
                      (clt/generate-toggle-token-set (tht/get-tokens-lib file) "foo/bar"))

          redo (thf/apply-changes file changes)
          redo-lib (tht/get-tokens-lib redo)
          undo (thf/apply-undo-changes redo changes)
          undo-lib (tht/get-tokens-lib undo)]
      (t/is (= #{ctob/hidden-theme-path} (ctob/get-active-theme-paths redo-lib)))
      (t/is (= #{} (:sets (ctob/get-hidden-theme redo-lib))))

      ;; Undo
      (t/is (= #{"/theme"} (ctob/get-active-theme-paths undo-lib)))))

  (t/testing "toggling an inactive set will switch to hidden theme without user sets"
    (let [file (setup-file #(-> %
                                (ctob/add-set (ctob/make-token-set :name "foo/bar"))
                                (ctob/add-theme (ctob/make-token-theme :name "theme"
                                                                       :sets #{"foo/bar"}))
                                (ctob/set-active-themes #{"/theme"})))
          changes (-> (pcb/empty-changes)
                      (pcb/with-library-data (:data file))
                      (clt/generate-toggle-token-set (tht/get-tokens-lib file) "foo/bar"))

          redo (thf/apply-changes file changes)
          redo-lib (tht/get-tokens-lib redo)
          undo (thf/apply-undo-changes redo changes)
          undo-lib (tht/get-tokens-lib undo)]
      (t/is (= #{ctob/hidden-theme-path} (ctob/get-active-theme-paths redo-lib)))
      (t/is (= #{} (:sets (ctob/get-hidden-theme redo-lib))))

      ;; Undo
      (t/is (= #{"/theme"} (ctob/get-active-theme-paths undo-lib)))))

  (t/testing "toggling an set with hidden theme already active will toggle set in hidden theme"
    (let [file (setup-file #(-> %
                                (ctob/add-set (ctob/make-token-set :name "foo/bar"))
                                (ctob/add-theme (ctob/make-hidden-theme))
                                (ctob/set-active-themes #{ctob/hidden-theme-path})))

          changes (-> (pcb/empty-changes)
                      (pcb/with-library-data (:data file))
                      (clt/generate-toggle-token-set-group (tht/get-tokens-lib file) ["foo"]))

          redo (thf/apply-changes file changes)
          redo-lib (tht/get-tokens-lib redo)
          undo (thf/apply-undo-changes redo changes)
          undo-lib (tht/get-tokens-lib undo)]
      (t/is (= (ctob/get-active-theme-paths redo-lib) (ctob/get-active-theme-paths undo-lib)))

      (t/is (= #{"foo/bar"} (:sets (ctob/get-hidden-theme redo-lib)))))))

(t/deftest set-token-theme-test
  (t/testing "delete token theme"
    (let [theme-id (uuid/next)
          file (setup-file #(-> %
                                (ctob/add-theme (ctob/make-token-theme :id theme-id
                                                                       :name "foo"
                                                                       :group "main"))))
          changes (-> (pcb/empty-changes)
                      (pcb/with-library-data (:data file))
                      (pcb/set-token-theme theme-id nil))

          redo (thf/apply-changes file changes)
          redo-lib (tht/get-tokens-lib redo)
          undo (thf/apply-undo-changes redo changes)
          undo-lib (tht/get-tokens-lib undo)]
      ;; Redo
      (t/is (nil? (ctob/get-theme redo-lib theme-id)))
      ;; Undo
      (t/is (some? (ctob/get-theme undo-lib theme-id)))))

  (t/testing "add token theme"
    (let [theme-id (uuid/next)
          theme (ctob/make-token-theme :id theme-id
                                       :name "foo"
                                       :group "main")
          file (setup-file identity)
          changes (-> (pcb/empty-changes)
                      (pcb/with-library-data (:data file))
                      (pcb/set-token-theme theme-id theme))
          redo (thf/apply-changes file changes)
          redo-lib (tht/get-tokens-lib redo)
          undo (thf/apply-undo-changes redo changes)
          undo-lib (tht/get-tokens-lib undo)]
      ;; Redo
      (t/is (some? (ctob/get-theme redo-lib theme-id)))
      ;; Undo
      (t/is (nil? (ctob/get-theme undo-lib theme-id)))))

  (t/testing "update token theme"
    (let [theme-id (uuid/next)
          prev-theme-name "foo"
          prev-theme (ctob/make-token-theme :id theme-id
                                            :name prev-theme-name
                                            :group "main")
          file (setup-file #(ctob/add-theme % prev-theme))
          new-theme-name "foo1"
          changes (-> (pcb/empty-changes)
                      (pcb/with-library-data (:data file))
                      (pcb/set-token-theme theme-id (ctob/rename prev-theme new-theme-name)))
          redo (thf/apply-changes file changes)
          redo-lib (tht/get-tokens-lib redo)
          redo-theme (ctob/get-theme redo-lib theme-id)
          undo (thf/apply-undo-changes redo changes)
          undo-lib (tht/get-tokens-lib undo)
          undo-theme (ctob/get-theme undo-lib theme-id)]
      ;; Redo
      (t/is (= new-theme-name (ctob/get-name redo-theme)))
      ;; Undo
      (t/is (= prev-theme-name (ctob/get-name undo-theme)))))

  (t/testing "toggling token theme updates using changes history"
    (let [theme-id (uuid/next)
          theme (ctob/make-token-theme :id theme-id
                                       :name "foo-theme"
                                       :group "main")
          set-name "bar-set"
          token-set (ctob/make-token-set :name set-name)
          file (setup-file #(-> %
                                (ctob/add-theme theme)
                                (ctob/add-set token-set)))
          theme' (assoc theme :sets #{set-name})
          changes (-> (pcb/empty-changes)
                      (pcb/with-library-data (:data file))
                      (pcb/set-token-theme theme-id theme'))
          changed-file (-> file
                           (thf/apply-changes changes)
                           (thf/apply-undo-changes changes)
                           (thf/apply-changes changes))
          changed-lib (tht/get-tokens-lib changed-file)]
      (t/is (= #{set-name}
               (-> changed-lib (ctob/get-theme theme-id) :sets))))))

(t/deftest set-token-test
  (t/testing "delete token"
    (let [set-name "foo"
          set-id (uuid/next)
          token-id (uuid/next)
          file (setup-file #(-> %
                                (ctob/add-set (ctob/make-token-set :id set-id
                                                                   :name set-name))
                                (ctob/add-token set-id (ctob/make-token {:name "to.delete.color.red"
                                                                         :id token-id
                                                                         :value "red"
                                                                         :type :color}))))
          changes (-> (pcb/empty-changes)
                      (pcb/with-library-data (:data file))
                      (pcb/set-token set-id token-id nil))

          redo (thf/apply-changes file changes)
          redo-lib (tht/get-tokens-lib redo)
          undo (thf/apply-undo-changes redo changes)
          undo-lib (tht/get-tokens-lib undo)]
      (t/is (nil? (ctob/get-token redo-lib set-id token-id)))
      ;; Undo
      (t/is (some? (ctob/get-token undo-lib set-id token-id)))))

  (t/testing "add token"
    (let [set-name "foo"
          set-id (uuid/next)
          token (ctob/make-token {:name "to.add.color.red"
                                  :value "red"
                                  :type :color})
          file (setup-file #(-> % (ctob/add-set (ctob/make-token-set :id set-id
                                                                     :name set-name))))
          changes (-> (pcb/empty-changes)
                      (pcb/with-library-data (:data file))
                      (pcb/set-token set-id (:id token) token))

          redo (thf/apply-changes file changes)
          redo-lib (tht/get-tokens-lib redo)
          undo (thf/apply-undo-changes redo changes)
          undo-lib (tht/get-tokens-lib undo)]
      (t/is (= token (ctob/get-token redo-lib set-id (:id token))))
      ;; Undo
      (t/is (nil? (ctob/get-token undo-lib set-id (:id token))))))

  (t/testing "update token"
    (let [set-name "foo"
          set-id (uuid/next)
          prev-token (ctob/make-token {:name "to.update.color.red"
                                       :value "red"
                                       :type :color})
          token (-> prev-token
                    (assoc :name "color.red.changed")
                    (assoc :value "blue"))
          file (setup-file #(-> %
                                (ctob/add-set (ctob/make-token-set :id set-id
                                                                   :name set-name))
                                (ctob/add-token set-id prev-token)))
          changes (-> (pcb/empty-changes)
                      (pcb/with-library-data (:data file))
                      (pcb/set-token set-id (:id prev-token) token))

          redo (thf/apply-changes file changes)
          redo-lib (tht/get-tokens-lib redo)
          undo (thf/apply-undo-changes redo changes)
          undo-lib (tht/get-tokens-lib undo)]
      (t/is (tht/token-data-eq? token (ctob/get-token redo-lib set-id (:id token))))
      ;; Undo
      (t/is (tht/token-data-eq? prev-token (ctob/get-token undo-lib set-id (:id prev-token)))))))

(t/deftest set-token-set-test
  (t/testing "delete token set"
    (let [set-name "foo"
          set-id (uuid/next)
          file (setup-file #(ctob/add-set % (ctob/make-token-set :id set-id :name set-name)))
          changes (-> (pcb/empty-changes)
                      (pcb/with-library-data (:data file))
                      (pcb/set-token-set set-id nil))

          redo (thf/apply-changes file changes)
          redo-lib (tht/get-tokens-lib redo)
          undo (thf/apply-undo-changes redo changes)
          undo-lib (tht/get-tokens-lib undo)]
      (t/is (not (ctob/set-path-exists? redo-lib [set-name])))
      ;; Undo
      (t/is (ctob/set-path-exists? undo-lib [set-name]))))

  (t/testing "add token set"
    (let [set-name "foo"
          set-id (uuid/next)
          token-set (ctob/make-token-set :id set-id :name set-name)
          file (setup-file identity)
          changes (-> (pcb/empty-changes)
                      (pcb/with-library-data (:data file))
                      (pcb/set-token-set set-id token-set))

          redo (thf/apply-changes file changes)
          redo-lib (tht/get-tokens-lib redo)
          undo (thf/apply-undo-changes redo changes)
          undo-lib (tht/get-tokens-lib undo)]
      (t/is (not (ctob/set-path-exists? undo-lib [set-name])))
      ;; Undo
      (t/is (ctob/set-path-exists? redo-lib [set-name]))))

  (t/testing "update token set"
    (let [set-name "foo"
          set-id (uuid/next)
          token-set (ctob/make-token-set :id set-id :name set-name)
          file (setup-file #(-> (ctob/add-set % token-set)))
          new-set-name "foo1"

          changes (-> (pcb/empty-changes)
                      (pcb/with-library-data (:data file))
                      (pcb/set-token-set set-id (ctob/rename token-set new-set-name)))

          redo (thf/apply-changes file changes)
          redo-lib (tht/get-tokens-lib redo)
          redo-token-set (ctob/get-set redo-lib set-id)

          undo (thf/apply-undo-changes redo changes)
          undo-lib (tht/get-tokens-lib undo)
          undo-token-set (ctob/get-set undo-lib set-id)]

      (t/is (= (ctob/get-name redo-token-set) new-set-name))
      ;; Undo
      (t/is (= (ctob/get-name undo-token-set) set-name)))))

(t/deftest generate-toggle-token-set-group-test
  (t/testing "toggling set group with no active sets inside will activate all child sets"
    (let [file (setup-file #(-> %
                                (ctob/add-set (ctob/make-token-set :name "foo/bar"))
                                (ctob/add-set (ctob/make-token-set :name "foo/bar/baz"))
                                (ctob/add-set (ctob/make-token-set :name "foo/bar/baz/baz-child"))
                                (ctob/add-theme (ctob/make-token-theme :name "theme"))
                                (ctob/set-active-themes #{"/theme"})))
          changes (-> (pcb/empty-changes)
                      (pcb/with-library-data (:data file))
                      (clt/generate-toggle-token-set-group (tht/get-tokens-lib file) ["foo" "bar"]))

          redo (thf/apply-changes file changes)
          redo-lib (tht/get-tokens-lib redo)
          undo (thf/apply-undo-changes redo changes)
          undo-lib (tht/get-tokens-lib undo)]
      (t/is (= #{ctob/hidden-theme-path} (ctob/get-active-theme-paths redo-lib)))
      (t/is (= #{"foo/bar/baz" "foo/bar/baz/baz-child"} (:sets (ctob/get-hidden-theme redo-lib))))

      ;; Undo
      (t/is (= #{"/theme"} (ctob/get-active-theme-paths undo-lib)))))

  (t/testing "toggling set group with partially active sets inside will deactivate all child sets"
    (let [file (setup-file #(-> %
                                (ctob/add-set (ctob/make-token-set :name "foo/bar"))
                                (ctob/add-set (ctob/make-token-set :name "foo/bar/baz"))
                                (ctob/add-set (ctob/make-token-set :name "foo/bar/baz/baz-child"))
                                (ctob/add-theme (ctob/make-token-theme :name "theme"
                                                                       :sets #{"foo/bar/baz"}))
                                (ctob/set-active-themes #{"/theme"})))

          changes (-> (pcb/empty-changes)
                      (pcb/with-library-data (:data file))
                      (clt/generate-toggle-token-set-group (tht/get-tokens-lib file) ["foo" "bar"]))

          redo (thf/apply-changes file changes)
          redo-lib (tht/get-tokens-lib redo)
          undo (thf/apply-undo-changes redo changes)
          undo-lib (tht/get-tokens-lib undo)]
      (t/is (= #{} (:sets (ctob/get-hidden-theme redo-lib))))
      (t/is (= #{ctob/hidden-theme-path} (ctob/get-active-theme-paths redo-lib)))

      ;; Undo
      (t/is (= #{"/theme"} (ctob/get-active-theme-paths undo-lib))))))

(t/deftest generate-move-token-set-test
  (t/testing "Ignore dropping set to the same position:"
    (let [file (setup-file #(-> %
                                (ctob/add-set (ctob/make-token-set :name "foo"))
                                (ctob/add-set (ctob/make-token-set :name "bar/baz"))))
          drop (partial clt/generate-move-token-set (pcb/empty-changes) (tht/get-tokens-lib file))]
      (t/testing "on top of identical"
        (t/is (= (pcb/empty-changes)
                 (drop {:from-index 0
                        :to-index 0
                        :position :top}))))
      (t/testing "on bottom of identical"
        (t/is (= (pcb/empty-changes)
                 (drop {:from-index 0
                        :to-index 0
                        :position :bot}))))
      (t/testing "on top of next to identical"
        (t/is (= (pcb/empty-changes)
                 (drop {:from-index 0
                        :to-index 1
                        :position :top}))))))

  (t/testing "Reorder sets when dropping next to a set:"
    (t/testing "at top"
      (let [file (setup-file #(-> %
                                  (ctob/add-set (ctob/make-token-set :name "foo"))
                                  (ctob/add-set (ctob/make-token-set :name "bar"))
                                  (ctob/add-set (ctob/make-token-set :name "baz"))))
            lib (tht/get-tokens-lib file)
            changes (clt/generate-move-token-set (pcb/empty-changes) lib {:from-index 1
                                                                          :to-index 0
                                                                          :position :top})
            redo (thf/apply-changes file changes)
            redo-sets (-> (tht/get-tokens-lib redo)
                          (ctob/get-set-names))
            undo (thf/apply-undo-changes redo changes)
            undo-sets (-> (tht/get-tokens-lib undo)
                          (ctob/get-set-names))]
        (t/is (= ["bar" "foo" "baz"] (vec redo-sets)))
        (t/testing "undo"
          (t/is (= (ctob/get-set-names lib) undo-sets)))))

    (t/testing "at bottom"
      (let [file (setup-file #(-> %
                                  (ctob/add-set (ctob/make-token-set :name "foo"))
                                  (ctob/add-set (ctob/make-token-set :name "bar"))
                                  (ctob/add-set (ctob/make-token-set :name "baz"))))
            lib (tht/get-tokens-lib file)
            changes (clt/generate-move-token-set (pcb/empty-changes) lib {:from-index 0
                                                                          :to-index 2
                                                                          :position :bot})
            redo (thf/apply-changes file changes)
            redo-sets (-> (tht/get-tokens-lib redo)
                          (ctob/get-set-names))
            undo (thf/apply-undo-changes redo changes)
            undo-sets (-> (tht/get-tokens-lib undo)
                          (ctob/get-set-names))]
        (t/is (= ["bar" "baz" "foo"] (vec redo-sets)))
        (t/testing "undo"
          (t/is (= (ctob/get-set-names lib) undo-sets)))))

    (t/testing "dropping out of set group"
      (let [file (setup-file #(-> %
                                  (ctob/add-set (ctob/make-token-set :name "foo/bar"))
                                  (ctob/add-set (ctob/make-token-set :name "foo"))))
            lib (tht/get-tokens-lib file)
            changes (clt/generate-move-token-set (pcb/empty-changes) lib {:from-index 1
                                                                          :to-index 0
                                                                          :position :top})
            redo (thf/apply-changes file changes)
            redo-sets (-> (tht/get-tokens-lib redo)
                          (ctob/get-set-names))
            undo (thf/apply-undo-changes redo changes)
            undo-sets (-> (tht/get-tokens-lib undo)
                          (ctob/get-set-names))]
        (t/is (= ["bar" "foo"] (vec redo-sets)))
        (t/testing "undo"
          (t/is (= (ctob/get-set-names lib) undo-sets)))))

    (t/testing "into set group"
      (let [file (setup-file #(-> %
                                  (ctob/add-set (ctob/make-token-set :name "foo/bar"))
                                  (ctob/add-set (ctob/make-token-set :name "foo"))))
            lib (tht/get-tokens-lib file)
            changes (clt/generate-move-token-set (pcb/empty-changes) lib {:from-index 2
                                                                          :to-index 1
                                                                          :position :bot})
            redo (thf/apply-changes file changes)
            redo-sets (-> (tht/get-tokens-lib redo)
                          (ctob/get-set-names))
            undo (thf/apply-undo-changes redo changes)
            undo-sets (-> (tht/get-tokens-lib undo)
                          (ctob/get-set-names))]
        (t/is (= ["foo/bar" "foo/foo"] (vec redo-sets)))
        (t/testing "undo"
          (t/is (= (ctob/get-set-names lib) undo-sets)))))

    (t/testing "edge-cases:"
      (t/testing "prevent overriding set to identical path"
        (let [file (setup-file #(-> %
                                    (ctob/add-set (ctob/make-token-set :name "foo/foo"))
                                    (ctob/add-set (ctob/make-token-set :name "foo"))))
              lib (tht/get-tokens-lib file)]
          (t/is (thrown?
                 #?(:cljs js/Error :clj Exception)
                 (clt/generate-move-token-set (pcb/empty-changes) lib {:from-index 2
                                                                       :to-index 0
                                                                       :position :bot})
                 #"move token set error: path exists"))
          (t/is (thrown?
                 #?(:cljs js/Error :clj Exception)
                 (clt/generate-move-token-set (pcb/empty-changes) lib {:from-index 2
                                                                       :to-index 1
                                                                       :position :bot})
                 #"move token set error: path exists"))))

      (t/testing "dropping below collapsed group doesnt add as child"
        (let [file (setup-file #(-> %
                                    (ctob/add-set (ctob/make-token-set :name "foo"))
                                    (ctob/add-set (ctob/make-token-set :name "foo/bar"))))
              lib (tht/get-tokens-lib file)
              changes (clt/generate-move-token-set (pcb/empty-changes) lib {:from-index 0
                                                                            :to-index 1
                                                                            :position :bot
                                                                            :collapsed-paths #{["foo"]}})
              redo (thf/apply-changes file changes)
              redo-sets (-> (tht/get-tokens-lib redo)
                            (ctob/get-set-names))
              undo (thf/apply-undo-changes redo changes)
              undo-sets (-> (tht/get-tokens-lib undo)
                            (ctob/get-set-names))]
          (t/is (= ["foo/bar" "foo"] (vec redo-sets)))
          (t/testing "undo"
            (t/is (= (ctob/get-set-names lib) undo-sets))))))))

(t/deftest generate-move-token-group-test
  (t/testing "Ignore dropping set group to the same position"
    (let [file (setup-file #(-> %
                                (ctob/add-set (ctob/make-token-set :name "foo"))
                                (ctob/add-set (ctob/make-token-set :name "bar/baz"))))
          drop (partial clt/generate-move-token-set-group (pcb/empty-changes) (tht/get-tokens-lib file))]
      (t/testing "on top of identical"
        (t/is (= (pcb/empty-changes)
                 (drop {:from-index 1
                        :to-index 1
                        :position :top}))))
      (t/testing "on bottom of identical"
        (t/is (= (pcb/empty-changes)
                 (drop {:from-index 1
                        :to-index 1
                        :position :bot}))))
      (t/testing "on top of next to identical"
        (t/is (= (pcb/empty-changes)
                 (drop {:from-index 1
                        :to-index 1
                        :position :top}))))))

  (t/testing "Move set groups"
    (t/testing "to top"
      (let [file (setup-file #(-> %
                                  (ctob/add-set (ctob/make-token-set :name "foo/foo"))
                                  (ctob/add-set (ctob/make-token-set :name "bar/bar"))
                                  (ctob/add-set (ctob/make-token-set :name "baz/baz"))))
            lib (tht/get-tokens-lib file)
            changes (clt/generate-move-token-set-group (pcb/empty-changes) lib {:from-index 2
                                                                                :to-index 0
                                                                                :position :top})
            redo (thf/apply-changes file changes)
            redo-sets (-> (tht/get-tokens-lib redo)
                          (ctob/get-set-names))
            undo (thf/apply-undo-changes redo changes)
            undo-sets (-> (tht/get-tokens-lib undo)
                          (ctob/get-set-names))]
        (t/is (= ["bar/bar" "foo/foo" "baz/baz"] (vec redo-sets)))

        (t/testing "undo"
          (t/is (= (ctob/get-set-names lib) undo-sets)))))

    (t/testing "to bottom"
      (let [file (setup-file #(-> %
                                  (ctob/add-set (ctob/make-token-set :name "foo/foo"))
                                  (ctob/add-set (ctob/make-token-set :name "bar"))))
            lib (tht/get-tokens-lib file)
            changes (clt/generate-move-token-set-group (pcb/empty-changes) lib {:from-index 0
                                                                                :to-index 2
                                                                                :position :bot})
            redo (thf/apply-changes file changes)
            redo-sets (-> (tht/get-tokens-lib redo)
                          (ctob/get-set-names))
            undo (thf/apply-undo-changes redo changes)
            undo-sets (-> (tht/get-tokens-lib undo)
                          (ctob/get-set-names))]
        (t/is (= ["bar" "foo/foo"] (vec redo-sets)))

        (t/testing "undo"
          (t/is (= (ctob/get-set-names lib) undo-sets)))))

    (t/testing "into set group"
      (let [file (setup-file #(-> %
                                  (ctob/add-set (ctob/make-token-set :name "foo/foo"))
                                  (ctob/add-set (ctob/make-token-set :name "bar/bar"))))
            lib (tht/get-tokens-lib file)
            changes (clt/generate-move-token-set-group (pcb/empty-changes) lib {:from-index 0
                                                                                :to-index 2
                                                                                :position :bot})
            redo (thf/apply-changes file changes)
            redo-sets (-> (tht/get-tokens-lib redo)
                          (ctob/get-set-names))
            undo (thf/apply-undo-changes redo changes)
            undo-sets (-> (tht/get-tokens-lib undo)
                          (ctob/get-set-names))]
        (t/is (= ["bar/foo/foo" "bar/bar"] (vec redo-sets)))
        (t/testing "undo"
          (t/is (= (ctob/get-set-names lib) undo-sets))))

      (t/testing "edge-cases:"
        (t/testing "prevent overriding set to identical path"
          (let [file (setup-file #(-> %
                                      (ctob/add-set (ctob/make-token-set :name "foo/identical/foo"))
                                      (ctob/add-set (ctob/make-token-set :name "identical/bar"))))
                lib (tht/get-tokens-lib file)]
            (t/is (thrown?
                   #?(:cljs js/Error :clj Exception)
                   (clt/generate-move-token-set-group (pcb/empty-changes) lib {:from-index 3
                                                                               :to-index 1
                                                                               :position :top})
                   #"move token set error: path exists"))))

        (t/testing "prevent dropping parent to child"
          (let [file (setup-file #(-> %
                                      (ctob/add-set (ctob/make-token-set :name "foo/bar/baz"))))
                lib (tht/get-tokens-lib file)]
            (t/is (thrown?
                   #?(:cljs js/Error :clj Exception)
                   (clt/generate-move-token-set-group (pcb/empty-changes) lib {:from-index 0
                                                                               :to-index 1
                                                                               :position :bot})
                   #"move token set error: parent-to-child"))))))))
