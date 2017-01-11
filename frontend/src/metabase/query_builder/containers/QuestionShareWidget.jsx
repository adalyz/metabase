/* @flow */
import React, { Component, PropTypes } from "react";
import { connect } from "react-redux";

import ShareWidget from "metabase/components/ShareWidget";

import { createPublicLink, deletePublicLink } from "../actions";

const mapDispatchToProps = {
    createPublicLink,
    deletePublicLink
}

@connect(null, mapDispatchToProps)
export default class QuestionShareWidget extends Component {
    render() {
        const { className, card, createPublicLink, deletePublicLink } = this.props;
        return (
            <ShareWidget
                className={className}
                type="question"
                uuid={card.public_uuid}
                onCreate={() => createPublicLink(card)}
                onDisable={() => deletePublicLink(card)}
            />
        );
    }
}
