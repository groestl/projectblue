package org.pgjava.sql.ast;

/** Sealed base for all statement nodes. */
public sealed interface Stmt extends Node
        permits SelectStmt, InsertStmt, UpdateStmt, DeleteStmt,
                CreateTableStmt, CreateTableAsStmt, DropTableStmt, AlterTableStmt,
                CreateIndexStmt, DropIndexStmt,
                CreateSchemaStmt, DropSchemaStmt,
                CreateSequenceStmt, AlterSequenceStmt, DropSequenceStmt,
                CreateViewStmt, DropViewStmt,
                CreateFunctionStmt, DropFunctionStmt, DoStmt,
                CreateTriggerStmt, DropTriggerStmt,
                CreateEnumStmt, CreateDomainStmt, AlterEnumStmt, DropTypeStmt,
                CallStmt,
                PrepareStmt, ExecuteStmt, DeallocateStmt,
                TruncateStmt,
                BeginStmt, CommitStmt, RollbackStmt,
                SavepointStmt, RollbackToSavepointStmt, ReleaseSavepointStmt,
                SetStmt, ShowStmt,
                ExplainStmt,
                CopyStmt,
                NotifyStmt, ListenStmt, UnlistenStmt,
                VacuumStmt,
                GrantStmt,
                LockTableStmt,
                UnsupportedStmt {}
