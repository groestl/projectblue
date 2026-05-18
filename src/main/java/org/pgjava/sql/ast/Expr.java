package org.pgjava.sql.ast;

/** Sealed base for all expression nodes. */
public sealed interface Expr extends Node
        permits IntegerLiteral, FloatLiteral, StringLiteral, BooleanLiteral, NullLiteral,
                TypedLiteral, IntervalLiteral,
                ColumnRef, ParamRef,
                BinaryOp, UnaryOp,
                CastExpr,
                FunctionCall,
                CaseExpr,
                SubLink,
                InExpr, InSubselect,
                BetweenExpr, LikeExpr,
                RowExpr,
                ArrayExpr, ArraySubselect,
                SubscriptExpr, FieldSelectExpr,
                CollateExpr,
                MinMaxExpr,
                GroupingExpr,
                SetToDefault,
                NamedArgExpr,
                ArrayAnyAllExpr {}
