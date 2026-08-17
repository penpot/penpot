import { Role } from "./Role";

/**
 * Marker base class for a bundle of named roles. A concrete bundle declares the
 * roles a particular configuration provides as `Role` fields; the foundation
 * operation that creates the configuration owns an instance of the bundle and
 * binds its roles into the situation, giving a case a typed handle to the
 * participants it acts on.
 */
export abstract class RoleBundle {
    /**
     * All roles declared on this bundle. Defaults to every `Role`-valued own
     * property, so concrete bundles need only declare their roles as fields.
     */
    allRoles(): Role[] {
        return Object.values(this).filter((value): value is Role => value instanceof Role);
    }
}
