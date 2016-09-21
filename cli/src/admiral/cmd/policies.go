/*
 * Copyright (c) 2016 VMware, Inc. All Rights Reserved.
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with separate copyright notices
 * and license terms. Your use of these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

package cmd

import (
	"errors"
	"fmt"
	"regexp"
	"strconv"
	"strings"

	"admiral/help"
	"admiral/policies"

	"github.com/spf13/cobra"
)

var policyIdError = errors.New("Policy ID not provided.")

func init() {
	initPolicyAdd()
	initPolicyUpdate()
	initPolicyList()
	initPolicyRemove()
}

var (
	cpuShares      string
	instances      string
	priority       string
	tenants        string
	resPoolID      string
	deplPolID      string
	memoryLimitStr string

	priorityInt        int32
	maxNumberInstances int32
	cpuSharesInt       int32
)

var policyAddCmd = &cobra.Command{
	Use:   "add [NAME]",
	Short: "Add policy",
	Long:  "Add policy",

	Run: func(cmd *cobra.Command, args []string) {
		output, err := RunPolicyAdd(args)
		processOutput(output, err)
	},
}

func initPolicyAdd() {
	policyAddCmd.Flags().StringVar(&cpuShares, "cpu", "", "CPU shares.")
	policyAddCmd.Flags().StringVar(&instances, "instances", "", "(Required) Instances")
	policyAddCmd.Flags().StringVar(&priority, "prio", "", "Priority")
	policyAddCmd.Flags().StringVar(&tenants, "group", "", "(Required) Group")
	policyAddCmd.Flags().StringVar(&resPoolID, "resource-pool", "", "(Required) Resource pool ID")
	policyAddCmd.Flags().StringVar(&deplPolID, "deployment-policy", "", "(Required) Deployment policy ID")
	policyAddCmd.Flags().StringVar(&memoryLimitStr, "memory", "0kb", "Memory limit. Default unit: kb. Units supported: kb/mb/gb. Example: 1024mb")
	PoliciesRootCmd.AddCommand(policyAddCmd)
}

func parseMemory(memory string) (size int64, err error) {
	reg := regexp.MustCompile("([0-9]+)([a-zA-Z]+)")
	results := reg.FindAllStringSubmatch(memory, -1)
	sizeStr := results[0][1]
	unit := results[0][2]
	size, err = strconv.ParseInt(sizeStr, 10, 64)
	switch strings.ToLower(unit) {
	case "kb":
		size = size * 1000
		return
	case "mb":
		size = size * 1000 * 1000
		return
	case "gb":
		size = size * 1000 * 1000 * 1000
		return
	}
	return 0, errors.New("Unable to parse the memory provided.")
}

func RunPolicyAdd(args []string) (string, error) {
	var (
		newID string
		err   error
		name  string
		ok    bool
	)
	if name, ok = ValidateArgsCount(args); !ok {
		return "", errors.New("Policy name not provided.")
	}
	memoryLimit, err := parseMemory(memoryLimitStr)
	if err != nil {
		return "", err
	}
	newID, err = policies.AddPolicy(name, cpuShares, instances, priority, tenants, resPoolID, deplPolID, memoryLimit)
	if err != nil {
		return "", err
	} else {
		return "Policy added: " + newID, err
	}
}

var policyListCmd = &cobra.Command{
	Use:   "ls",
	Short: "Lists existing policies.",
	Long:  "Lists existing policies.",

	Run: func(cmd *cobra.Command, args []string) {
		RunPolicyList(args)
	},
}

func initPolicyList() {
	policyListCmd.SetUsageTemplate(help.DefaultUsageListTemplate)
	PoliciesRootCmd.AddCommand(policyListCmd)
}

func RunPolicyList(args []string) {
	pl := &policies.PolicyList{}
	count := pl.FetchPolices()
	if count < 0 {
		fmt.Println("n/a")
	}
	pl.Print()
}

var policyRemoveCmd = &cobra.Command{
	Use:   "rm [POLICY-ID]",
	Short: "Remove existing pool",
	Long:  "Remove existing pool",

	Run: func(cmd *cobra.Command, args []string) {
		output, err := RunPolicyRemove(args)
		processOutput(output, err)
	},
}

func initPolicyRemove() {
	PoliciesRootCmd.AddCommand(policyRemoveCmd)
}

func RunPolicyRemove(args []string) (string, error) {
	var (
		newID string
		err   error
		id    string
		ok    bool
	)

	if id, ok = ValidateArgsCount(args); !ok {
		return "", policyIdError
	}
	newID, err = policies.RemovePolicyID(id)

	if err != nil {
		return "", err
	} else {
		return "Policy removed: " + newID, err
	}
}

var policyUpdateCmd = &cobra.Command{
	Use:   "update [POLICY-ID]",
	Short: "Update policy.",
	Long:  "Update policy.",

	Run: func(cmd *cobra.Command, args []string) {
		output, err := RunPolicyUpdate(args)
		processOutput(output, err)
	},
}

func initPolicyUpdate() {
	policyUpdateCmd.Flags().StringVar(&newName, "name", "", "New name")
	policyUpdateCmd.Flags().Int32Var(&cpuSharesInt, "cpu", -1, "New CPU shares.")
	policyUpdateCmd.Flags().Int32Var(&maxNumberInstances, "instances", -1, "New instances")
	policyUpdateCmd.Flags().Int32Var(&priorityInt, "prio", -1, "New priority")
	policyUpdateCmd.Flags().StringVar(&tenants, "group", "", "New group")
	policyUpdateCmd.Flags().StringVar(&resPoolID, "resource-pool", "", "New resource pool ID")
	policyUpdateCmd.Flags().StringVar(&deplPolID, "deployment-policy", "", "New deployment policy ID")
	policyUpdateCmd.Flags().StringVar(&memoryLimitStr, "memory", "0kb", "New memory limit. Default unit: kb. Units supported: kb/mb/gb. Example: 1024mb")
	PoliciesRootCmd.AddCommand(policyUpdateCmd)
}

func RunPolicyUpdate(args []string) (string, error) {
	var (
		newID string
		err   error
		id    string
		ok    bool
	)
	memoryLimit, err := parseMemory(memoryLimitStr)
	if err != nil {
		return "", err
	}

	if id, ok = ValidateArgsCount(args); !ok {
		return "", policyIdError
	}
	newID, err = policies.EditPolicyID(id, newName, tenants, resPoolID, deplPolID, cpuSharesInt, maxNumberInstances, priorityInt, memoryLimit)

	if err != nil {
		return "", err
	} else {
		return "Policy updates: " + newID, err
	}
}
