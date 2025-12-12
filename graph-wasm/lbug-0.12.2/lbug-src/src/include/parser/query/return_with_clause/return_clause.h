#pragma once

#include "projection_body.h"

namespace lbug {
namespace parser {

class ReturnClause {
public:
    explicit ReturnClause(ProjectionBody projectionBody)
        : projectionBody{std::move(projectionBody)} {}
    DELETE_COPY_DEFAULT_MOVE(ReturnClause);

    virtual ~ReturnClause() = default;

    inline const ProjectionBody* getProjectionBody() const { return &projectionBody; }

private:
    ProjectionBody projectionBody;
};

} // namespace parser
} // namespace lbug
