import React, { Component } from 'react';
import { Provider } from 'react-redux';
import TodoApp from './TodoApp';

export default class TodoAppRoot extends Component {
  render() {
    const { store } = this.props;
    return (
      <Provider store={store}>
        <TodoApp />
      </Provider>
    );
  }
}