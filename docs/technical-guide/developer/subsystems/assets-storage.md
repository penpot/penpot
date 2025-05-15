---
title: Assets storage
desc: Learn about assets storage, API, object buckets, sharing, and garbage collection. See Penpot's technical guide for developers. Try Penpot - It's free.
---

# Assets storage

The [storage.clj](https://github.com/penpot/penpot/blob/develop/backend/src/app/storage.clj)
is a module that manages storage of binary objects. It's a generic utility
that may be used for any kind of user uploaded files. Currently:

 * Image assets in Penpot files.
 * Uploaded fonts.
 * Profile photos of users and teams.

There is an abstract interface and several implementations (or **backends**),
depending on where the objects are actually stored:

 * <code class="language-clojure">:assets-fs</code> stores ojects in the file system, under a given base path.
 * <code class="language-clojure">:assets-s3</code> stores them in any cloud storage with an AWS-S3 compatible
   interface.
 * <code class="language-clojure">:assets-db</code> stores them inside the PostgreSQL database, in a special table
   with a binary column.

## Storage API

The **StorageObject** record represents one stored object. It contains the
metadata, that is always stored in the database (table <code class="language-clojure">storage_object</code>),
while the actual object data goes to the backend.

 * <code class="language-clojure">:id</code> is the identifier you use to reference the object, may be stored
   in other places to represent the relationship with other element.
 * <code class="language-clojure">:backend</code> points to the backend where the object data resides.
 * <code class="language-clojure">:created-at</code> is the date/time of object creation.
 * <code class="language-clojure">:deleted-at</code> is the date/time of object marked for deletion (see below).
 * <code class="language-clojure">:expired-at</code> allows to create objects that are automatically deleted
   at some time (useful for temporary objects).
 * <code class="language-clojure">:touched-at</code> is used to check objects that may need to be deleted (see
   below).

Also more metadata may be attached to objects, such as the <code class="language-clojure">:content-type</code> or
the <code class="language-clojure">:bucket</code> (see below).

You can use the API functions to manipulate objects. For example <code class="language-clojure">put-object!</code>
to create a new one, <code class="language-clojure">get-object</code> to retrieve the StorageObject,
<code class="language-clojure">get-object-data</code> or <code class="language-clojure">get-object-bytes</code> to read the binary contents, etc.

For profile photos or fonts, the object id is stored in the related table,
without further ado. But for file images, one more indirection is used. The
**file-media-object** is an abstraction that represents one image uploaded
by the user (in the future we may support other multimedia types). It has its
own database table, and references two <code class="language-clojure">StorageObjects</code>, one for the original
file and another one for the thumbnail. Image shapes contains the id of the
<code class="language-clojure">file-media-object</code> with the <code class="language-clojure">:is-local</code> property as true. Image assets in the
file library also have a <code class="language-clojure">file-media-object</code> with <code class="language-clojure">:is-local</code> false,
representing that the object may be being used in other files.

## Serving objects

Stored objects are always served by Penpot (even if they have a public URL,
like when <code class="language-clojure">:s3</code> storage are used). We have an endpoint <code class="language-text">/assets</code> with three
variants:

```bash
/assets/by-id/<uuid>
/assets/by-file-media-id/<uuid>
/assets/by-file-media-id/<uuid>/thumbnail
```

They take an object and retrieve its data to the user. For <code class="language-clojure">:db</code> backend, the
data is extracted from the database and served by the app. For the other ones,
we calculate the real url of the object, and pass it to our **nginx** server,
via special HTTP headers, for it to retrieve the data and serve it to the user.

This is the same in all environments (devenv, production or on premise).

## Object buckets

Obects may be organized in **buckets**, that are a kind of "intelligent" folders
(not related to AWS-S3 buckets, this is a Penpot internal concept).

The storage module may use the bucket (hardcoded) to make special treatment to
object, such as storing in a different path, or guessing how to know if an object
is referenced from other place.

## Sharing and deleting objects

To save storage space, duplicated objects wre shared. So, if for example
several users upload the same image, or a library asset is instantiated many
times, even by different users, the object data is actuall stored only once.

To achieve this, when an object is uploaded, its content is hashed, and the
hash compared with other objects in the same bucket. If there is a match,
the <code class="language-clojure">StorabeObject</code> is reused. Thus, there may be different, unrelated, shapes
or library assets whose <code class="language-clojure">:object-id</code> is the same.

### Garbage collector and reference count

Of course, when objects are shared, we cannot delete it directly when the
associated item is removed or unlinked. Instead, we need some mechanism to
track the references, and a garbage collector that deletes any object that
is no longer referenced.

We don't use explicit reference counts or indexes. Instead, the storage system
is intelligent enough to search, depending on the bucket (one for profile
photos, other for file media objects, etc.) if there is any element that is
using the object. For example, in the first case we look for user or team
profiles where the <code class="language-clojure">:photo-id</code> field matches the object id.

When one item stops using one storage object (e. g. an image shape is deleted),
we mark the object as <code class="language-clojure">:touched</code>. A periodic task revises all touched objectsm
checking if they are still referenced in other places. If not, they are marked
as :deleted. They're preserved in this state for some time (to allow "undeletion"
if the user undoes the change), and eventually, another garbage collection task
definitively deletes it, both in the backend and in the database table.

For <code class="language-clojure">file-media-objects</code>, there is another collector, that periodically checks
if a media object is referenced by any shape or asset in its file. If not, it
marks the object as <code class="language-clojure">:touched</code> triggering the process described above.

